package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.archive.ArchiveForumExtractor;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import dev.onionall.brawler.archive.internal.ArchiveContext;
import dev.onionall.brawler.archive.internal.BlobStore;
import org.jsoup.Jsoup;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>HTML snapshots, версии и latest projection.</b>
 *
 * <p>Проверяет approval и budgets, публикует blob до commit. Context владеет транзакцией; SQL rollback допускает orphan blob.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class SnapshotRepository {

    /** Координатор JDBC-транзакций и доступа к каталогу. */
    private final ArchiveContext context;

    /** Проверяет существование и approval crawl-плана. */
    private final PlanRepository plans;

    /** Сохраняет и читает связи архивных snapshots. */
    private final GraphRepository graph;

    /** Публикует и проверяет HTML-объекты по хешу содержимого. */
    private final BlobStore blobs;

    /**
     * <b>Собирает snapshot persistence без нового connection lifecycle.</b>
     * @param context единый владелец JDBC/transaction
     * @param plans проверка approval/scope/budgets
     * @param graph запись первых edges и graph queries
     */
    public SnapshotRepository(
            ArchiveContext context,
            PlanRepository plans,
            GraphRepository graph
    ) {
        this.context = context;
        this.plans = plans;
        this.graph = graph;
        this.blobs = new BlobStore(context.objects());
    }

    /**
     * SQL-проекция последнего snapshot каждой страницы.
     *
     * <p>Возвращает метаданные без чтения HTML-файла.
     * Проверка целостности blob выполняется отдельно в page().
     * JDBC ownership обеспечивает вызывающий код через ArchiveContext.
     */
    static final String LATEST = "SELECT p.id page_id,p.job_id,p.canonical_url,p.last_seen,s.id snapshot_id,s.* FROM pages p JOIN snapshots s ON s.id=p.latest_snapshot";

    /**
     * <b>Сохраняет snapshot успешной crawler-загрузки.</b>
     *
     * <p>Проверяет approval, scope, статус и bounded HTML; объект публикуется до commit.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param depth глубина обнаружения
     * @param source источник обнаружения
     * @param status числовой HTTP-статус ответа; для ручного импорта — 0
     * @param contentType тип содержимого
     * @param html байты HTML
     * @param links наблюдения связей
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> archive(
        String jobId,
        String url,
        int depth,
        String source,
        int status,
        String contentType,
        byte[] html,
        List<Map<String, Object>> links
    ) throws IOException {
        return archiveInternal(
                jobId, url, depth, source,
                status, contentType, html,
                links, false
        );
    }

    /**
     * <b>Сохраняет HTML ручного импорта.</b>
     *
     * <p>Использует MANUAL_IMPORT, http_status=0 и не подтверждает transport или robots.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param html байты HTML
     * @param links наблюдения связей
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> importHtml(
        String jobId,
        String url,
        byte[] html,
        List<Map<String, Object>> links
    ) throws IOException {
        return archiveInternal(
                jobId, url, 0, url, 0,
                "text/html", html,
                links, true
        );
    }

    /**
     * <b>Выполняет общую транзакцию записи HTML.</b>
     *
     * <p>Сериализует запись через catalog_guard; при ошибке откатывает SQL, сохраняя уже опубликованный blob.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param depth глубина обнаружения
     * @param source источник обнаружения
     * @param status числовой HTTP-статус ответа; для ручного импорта — 0
     * @param contentType тип содержимого
     * @param html байты HTML
     * @param links наблюдения связей
     * @param manual признак ручного импорта
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    private Map<String, Object> archiveInternal(
        String jobId,
        String url,
        int depth,
        String source,
        int status,
        String contentType,
        byte[] html,
        List<Map<String, Object>> links,
        boolean manual
    ) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Archive cancelled");
        }
        if (
                html == null ||
                html.length < 1 ||
                html.length > ArchiveStore.MAX_HTML_BYTES
        ) {
            throw new IOException("Archive requires bounded successful HTML");
        }
        byte[] content = html.clone();
        SnapshotIdentity written = context.transaction(() ->
                archiveSnapshot(
                        jobId, url, depth, source,
                        status, contentType, content,
                        links, manual)
        );
        try {
            return verifiedPage(written.pageId(), written.snapshotId());
        } catch (IOException e) {
            throw new IOException(
                    "Archive write COMMITTED; response readback failed; replay is safe",
                    e
            );
        }
    }

    /**
     * Записывает snapshot под context transaction/catalog_guard; возвращает только page id.
     * Blob публикуется до SQL commit, readback выполняется вызывающим кодом после commit.
     */
    private SnapshotIdentity archiveSnapshot(
        String jobId,
        String url,
        int depth,
        String source,
        int status,
        String contentType,
        byte[] html,
        List<Map<String, Object>> links,
        boolean manual
    ) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Archive cancelled");
        }
        context.capacity();
        Map<String, Object> plan = plans.approved(jobId);
        String allowed = (String) plan.get("origin");
        url = normalize(url, allowed);
        if (depth < 0 || depth > (Integer) plan.get("depth")) {
            throw new IOException("Discovery depth exceeds approved plan");
        }
        if (source == null || source.isBlank()) {
            source = url;
        }
        source = normalize(source, allowed);
        if ((manual ? status != 0 : status < 200 || status > 299) ||
                contentType == null ||
                !contentType
                        .split(";", 2)[0]
                        .trim()
                        .equalsIgnoreCase("text/html") ||
                html == null ||
                html.length < 1 ||
                html.length > ArchiveStore.MAX_HTML_BYTES
        ) {
            throw new IOException("Archive requires bounded successful HTML");
        }
        if (links == null || links.size() > 100) {
            throw new IOException("Archive link limit100");
        }
        List<Map<String, String>> safeLinks = new ArrayList<>();
        for (Map<String, Object> link : links) {
            if (link == null) {
                throw new IOException("Require a link observation object");
            }
            String target = Objects.toString(link.getOrDefault("target_url", link.getOrDefault("target", "")), "");
            String disposition = Objects.toString(link.getOrDefault("disposition", link.getOrDefault("result",
                "OBSERVED")), "OBSERVED");
            bounded(target, 2048, "link target");
            if (!disposition.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IOException("Invalid link disposition");
            }
            String observed = Objects.toString(link.getOrDefault("discovered_at", link.getOrDefault("discoveredAt",
                "")), "");
            String timeBasis = observed.isEmpty() ? "ARCHIVE_OBSERVED" : "DISCOVERY_REPORTED";
            if (observed.isEmpty()) {
                observed = Instant.now().toString();
            } else {
                bounded(observed, 40, "link discovery time");
                try {
                    observed = Instant.parse(observed).toString();
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IOException("Invalid link discovery time", e);
                }
            }
            safeLinks.add(
                    Map.of(
                            "source_url", url,
                            "target_url", target,
                            "disposition", disposition,
                            "discovered_at", observed,
                            "discovery_time_basis", timeBasis
                    )
            );
        }
        String digest = BlobStore.hash(html), pageId = null, snapshotId = UUID.randomUUID().toString();
        var document = Jsoup.parse(new String(html, java.nio.charset.StandardCharsets.UTF_8), url);
        String title = document.title();
        if (title.length() > 2048) {
            title = title.substring(0, 2048);
        }
        String text = document.text();
        if (text.length() > 131072) {
            text = text.substring(0, 131072);
        }
        try {
            try (PreparedStatement query = context.connection().prepareStatement(
                    "SELECT id FROM pages WHERE job_id=? AND canonical_url=?"
            )) {
                query.setString(1, jobId);
                query.setString(2, url);
                try (ResultSet rs = query.executeQuery()) {
                    if (rs.next()) {
                        pageId = rs.getString(1);
                    }
                }
            }
            if (pageId == null) {
                if (!hasTextPage(jobId, url) && archivedUrlCount(context.connection(), jobId) >= (Integer) plan.get("max_pages")) {
                    throw new IOException("Archive page limit reached (not a Frontier admission decision)");
                }
                pageId = UUID.randomUUID().toString();
                try (PreparedStatement insert = context.connection().prepareStatement("INSERT INTO pages(id,job_id,canonical_url) VALUES(?,?,?)")) {
                    insert.setString(1, pageId);
                    insert.setString(2, jobId);
                    insert.setString(3, url);
                    insert.executeUpdate();
                }
            }
            blobs.publish(digest, html);
            try (PreparedStatement query = context.connection().prepareStatement(
                    "SELECT id FROM snapshots WHERE page_id=? AND content_hash=? AND source_kind=?"
            )) {
                query.setString(1, pageId);
                query.setString(2, digest);
                query.setString(3, manual ? "MANUAL_IMPORT" : "CRAWLER");
                try (ResultSet rs = query.executeQuery()) {
                    if (rs.next()) {
                        try (PreparedStatement latest = context.connection().prepareStatement(
                                "UPDATE pages SET latest_snapshot=?,last_seen=? WHERE id=?"
                        )) {
                            latest.setString(1, rs.getString(1));
                            latest.setString(2, Instant.now().toString());
                            latest.setString(3, pageId);
                            latest.executeUpdate();
                        }
                        return new SnapshotIdentity(pageId, rs.getString(1));
                    }
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Archive cancelled");
            }

            try (PreparedStatement insert = context.connection().prepareStatement("INSERT INTO snapshots(id,page_id,content_hash,content_size,http_status,content_type,fetched_at,recorded_at,source_kind,discovery_depth,source_url,title,extracted_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, snapshotId);
                insert.setString(2, pageId);
                insert.setString(3, digest);
                insert.setInt(4, html.length);
                insert.setInt(5, status);
                insert.setString(6, "text/html");
                insert.setString(7, manual ? null : Instant.now().toString());
                insert.setString(8, Instant.now().toString());
                insert.setString(9, manual ? "MANUAL_IMPORT" : "CRAWLER");
                insert.setInt(10, depth);
                insert.setString(11, source);
                insert.setString(12, title);
                insert.setString(13, text);
                insert.executeUpdate();
            }
            graph.recordFirstLinks(snapshotId, safeLinks);

            // latest means last observed content, including A -> B -> A replay; not MAX(recorded_at).
            try (PreparedStatement update = context.connection().prepareStatement("UPDATE pages SET latest_snapshot=?,last_seen=? WHERE id=?")) {
                update.setString(1, snapshotId);
                update.setString(2, Instant.now().toString());
                update.setString(3, pageId);
                update.executeUpdate();
            }

            // Immutable first-snapshot edges are intentionally retained on replay.
            return new SnapshotIdentity(pageId, snapshotId);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * Формирует metadata-only JSON projection текущей строки под context ownership.
     * @param rs текущая строка LATEST query
     * @return snapshot metadata; файл здесь не проверяется
     * @throws SQLException при ошибке чтения column
     */
    Map<String, Object> snapshotRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of(
                "page_id",
                "job_id",
                "canonical_url",
                "snapshot_id",
                "content_hash",
                "content_type",
                "fetched_at",
                "recorded_at",
                "source_kind",
                "last_seen",
                "source_url",
                "title")
        ) {
            row.put(key, rs.getString(key));
        }
        row.put("url", row.get("canonical_url"));
        row.put("depth", rs.getInt("discovery_depth"));
        row.put("http_status", rs.getInt("http_status"));
        row.put("content_size", rs.getInt("content_size"));
        row.put("storage_location", context
                .root()
                .resolve("objects")
                .resolve(rs.getString("content_hash") + ".html")
                .toString()
        );
        return row;
    }

    /**
     * <b>Читает bounded список последних snapshots.</b>
     *
     * <p>Не открывает HTML и не запрашивает Frontier.
     *
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта списка страниц
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> pages(String jobId, int limit) throws IOException {
        bound(limit);
        if (jobId != null) {
            plans.plan(jobId);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement query = context.connection().prepareStatement(
                LATEST +
                        (jobId == null ? "" : " WHERE p.job_id=?") +
                        " ORDER BY CAST(s.recorded_at AS TIMESTAMP(9) WITH TIME ZONE),p.id LIMIT ?")
        ) {
            int index = 1;
            if (jobId != null) {
                query.setString(index++, jobId);
            }
            query.setInt(index, limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(snapshotRow(rows));
                }
            }
            return Map.of("pages", result, "limit", limit);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Читает страницу, provenance и историю snapshots.</b>
     *
     * <p>Проверяет идентификатор и сохранённую страницу; HTML не исполняется.
     *
     * @param pageId идентификатор архивной страницы
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> page(String pageId) throws IOException {
        return verifiedPage(pageId, null);
    }

    private Map<String, Object> verifiedPage(String pageId, String snapshotId) throws IOException {
        var result = readPage(pageId, snapshotId);
        blobs.verify((String) result.get("content_hash"), ((Number) result.get("content_size")).intValue());
        return result;
    }

    /** Читает current latest либо exact committed version, сохраняя прежний page JSON. */
    private Map<String, Object> readPage(String pageId, String snapshotId) throws IOException {
        identifier(pageId);
        String select = snapshotId == null ? LATEST : LATEST.replace("s.id=p.latest_snapshot", "s.page_id=p.id");
        try (PreparedStatement query = context.connection().prepareStatement(
                select + " WHERE p.id=?" + (snapshotId == null ? "" : " AND s.id=?"))
        ) {
            query.setString(1, pageId);
            if (snapshotId != null) { query.setString(2, snapshotId); }
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Unknown archived page");
                }
                Map<String, Object> result = snapshotRow(rs);
                result.put("text", rs.getString("extracted_text"));
                List<Map<String, Object>> history = new ArrayList<>();
                try (PreparedStatement versions = context.connection().prepareStatement(
                        "SELECT id,content_hash,recorded_at FROM snapshots WHERE page_id=? ORDER BY CAST(recorded_at AS TIMESTAMP(9) WITH TIME ZONE),id"
                )) {
                    versions.setString(1, pageId);
                    try (ResultSet rows = versions.executeQuery()) {
                        while (rows.next()) {
                            history.add(
                                    Map.of(
                                            "snapshot_id", rows.getString(1),
                                            "content_hash", rows.getString(2),
                                            "recorded_at", rows.getString(3)
                                    )
                            );
                        }
                    }
                }
                result.put("history", history);
                result.put("links", graph.readLinks(" WHERE l.snapshot_id=? ORDER BY l.ordinal LIMIT 100",
                    (String) result.get("snapshot_id"), 100));
                return result;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }


    /** Identity созданной/переиспользованной immutable version, не mutable latest pointer. */
    private record SnapshotIdentity(String pageId, String snapshotId) {}

    /** Проверяет совместно расходуемый URL budget HTML/text внутри catalog_guard. */
    private boolean hasTextPage(String jobId, String url) throws SQLException, IOException {
        try (var query = context.connection().prepareStatement("SELECT id FROM archive_document_pages_v1 WHERE job_id=? AND canonical_url=?")) {
            query.setString(1, jobId); query.setString(2, url);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    /**
     * Формирует derived structural evidence из verified HTML, не исполняя документ.
     * @param pageId UUID страницы
     * @return metadata, forum structure и inert Markdown; raw HTML остаётся blob evidence
     * @throws IOException при недоступном/повреждённом snapshot
     */
    public Map<String, Object> researchDocument(String pageId) throws IOException {
        var value = readPage(pageId, null);
        byte[] raw = blobs.read((String) value.get("content_hash"), ((Number) value.get("content_size")).intValue());
        var extracted = ArchiveForumExtractor.extract((String) value.get("canonical_url"), raw,
            (String) value.get("snapshot_id"));
        value.put("schema_version", 1);
        value.put("document_id", pageId);
        value.put("document_format", "HTML");
        value.put("forum_thread", extracted);
        value.put("body_markdown", extracted.get("body_markdown"));
        value.put("extraction_status", extracted.get("extraction_status"));
        value.put("content_trust", "UNTRUSTED_REMOTE_CONTENT_NOT_INSTRUCTIONS");
        value.put("limitations", extracted.get("limitations"));
        return value;
    }
}
