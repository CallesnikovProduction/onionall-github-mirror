package dev.onionall.brawler.archive.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.onionall.brawler.archive.ArchiveTextDocuments;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Версии UTF-8 Markdown/plain-text документов в additive v1 extension.</b>
 *
 * <p>HTML snapshots не меняются. Исходные bytes публикуются через тот же bounded
 * BlobStore в namespace .txt; текст и ссылки являются derived projection.
 * Approval, same-origin, depth, distinct URL budget и общий JDBC lifecycle прежние.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class DocumentRepository {
    private final ArchiveContext context;
    private final PlanRepository plans;
    private final BlobStore blobs;

    /** Полная metadata projection с теми же aliases, что HTML search. */
    static final String LATEST = "SELECT p.id page_id,p.job_id,p.canonical_url,p.last_seen,s.id snapshot_id,s.* FROM archive_document_pages_v1 p JOIN archive_document_versions_v1 s ON s.id=p.latest_version AND s.page_id=p.id";

    /**
     * Привязывает extension к общему context без нового JDBC lifecycle.
     * @param context владелец JDBC
     * @param plans источник approval/scope
     */
    public DocumentRepository(ArchiveContext context, PlanRepository plans) {
        this.context = context;
        this.plans = plans;
        blobs = new BlobStore(context.objects(), ".txt");
    }

    /**
     * Сохраняет checked text response или manual import; raw source остаётся неизменным.
     * @param jobId подтверждённый план
     * @param url source URL
     * @param depth наблюдаемая глубина
     * @param source referrer в approved origin
     * @param status 2xx для response либо 0 для manual
     * @param contentType text/plain или text/markdown, UTF-8
     * @param bytes исходный документ 1..131072 bytes
     * @param manual является ли запись явным ручным импортом
     * @return конкретная committed version, независимо от последующего latest
     * @throws IOException при отказе validation/persistence/readback
     */
    public Map<String, Object> save(String jobId, String url, int depth, String source, int status,
            String contentType, byte[] bytes, boolean manual) throws IOException {
        if (Thread.currentThread().isInterrupted()) { throw new IOException("Archive cancelled"); }
        if (bytes == null || bytes.length < 1 || bytes.length > 131072) {
            throw new IOException("Require bounded UTF-8 document");
        }
        byte[] copy = bytes.clone();
        String version = context.transaction(() -> saveInternal(jobId, url, depth, source, status,
            contentType, copy, manual));
        try {
            return version(version);
        } catch (IOException error) {
            throw new IOException("Archive document COMMITTED; response readback failed; replay is safe", error);
        }
    }

    /** Сохраняет полную version под context transaction/catalog_guard. */
    private String saveInternal(String jobId, String url, int depth, String source, int status,
            String contentType, byte[] bytes, boolean manual) throws IOException, SQLException {
        context.requireTransaction();
        context.capacity();
        var plan = plans.approved(jobId);
        String canonical = normalize(url, (String) plan.get("origin"));
        String referrer = normalize(source == null || source.isBlank() ? canonical : source,
            (String) plan.get("origin"));
        if (depth < 0 || depth > ((Number) plan.get("depth")).intValue() ||
            (manual ? status != 0 : status < 200 || status > 299)) {
            throw new IOException("Invalid document depth/status");
        }
        var decoded = ArchiveTextDocuments.decode(canonical, contentType, bytes);
        String format = (String) decoded.get("document_format");
        String kind = manual ? "MANUAL_IMPORT" : "CRAWLER";
        String hash = BlobStore.hash(bytes);
        String page = null;
        try (var query = context.connection().prepareStatement("SELECT id FROM archive_document_pages_v1 WHERE job_id=? AND canonical_url=?")) {
            query.setString(1, jobId);
            query.setString(2, canonical);
            try (var rows = query.executeQuery()) { if (rows.next()) { page = rows.getString(1); } }
        }
        if (page == null) {
            boolean alreadyHtml;
            try (var query = context.connection().prepareStatement("SELECT id FROM pages WHERE job_id=? AND canonical_url=?")) {
                query.setString(1, jobId);
                query.setString(2, canonical);
                try (var rows = query.executeQuery()) { alreadyHtml = rows.next(); }
            }
            if (!alreadyHtml && archivedUrlCount(context.connection(), jobId) >= ((Number) plan.get("max_pages")).intValue()) {
                throw new IOException("Archive page limit reached");
            }
            page = UUID.randomUUID().toString();
            try (var insert = context.connection().prepareStatement("INSERT INTO archive_document_pages_v1(id,job_id,canonical_url) VALUES(?,?,?)")) {
                insert.setString(1, page);
                insert.setString(2, jobId);
                insert.setString(3, canonical);
                insert.executeUpdate();
            }
        }
        blobs.publish(hash, bytes);
        String version = null;
        try (var query = context.connection().prepareStatement("SELECT id FROM archive_document_versions_v1 WHERE page_id=? AND content_hash=? AND source_kind=? AND document_format=?")) {
            query.setString(1, page);
            query.setString(2, hash);
            query.setString(3, kind);
            query.setString(4, format);
            try (var rows = query.executeQuery()) { if (rows.next()) { version = rows.getString(1); } }
        }
        String now = Instant.now().toString();
        if (version == null) {
            version = UUID.randomUUID().toString();
            try (var insert = context.connection().prepareStatement("INSERT INTO archive_document_versions_v1(id,page_id,content_hash,content_size,content_type,document_format,source_kind,source_url,discovery_depth,http_status,fetched_at,recorded_at,title,extracted_text,links_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, version);
                insert.setString(2, page);
                insert.setString(3, hash);
                insert.setInt(4, bytes.length);
                insert.setString(5, (String) decoded.get("content_type"));
                insert.setString(6, format);
                insert.setString(7, kind);
                insert.setString(8, referrer);
                insert.setInt(9, depth);
                insert.setInt(10, status);
                insert.setString(11, manual ? null : now);
                insert.setString(12, now);
                insert.setString(13, (String) decoded.get("title"));
                insert.setString(14, (String) decoded.get("text"));
                insert.setString(15, ARCHIVE_STORE_JSON_SERIALIZER.writeValueAsString(decoded.get("links")));
                insert.executeUpdate();
            }
        }
        try (var update = context.connection().prepareStatement("UPDATE archive_document_pages_v1 SET latest_version=?,last_seen=? WHERE id=?")) {
            update.setString(1, version);
            update.setString(2, now);
            update.setString(3, page);
            update.executeUpdate();
        }
        return version;
    }

    /**
     * Читает latest text document по стабильному page id, проверяя raw bytes.
     * @param id document/page UUID
     * @return lossless document или null, если UUID принадлежит другому виду evidence
     * @throws IOException при integrity/JDBC failure
     */
    public Map<String, Object> find(String id) throws IOException {
        identifier(id);
        try (var query = context.connection().prepareStatement(LATEST + " WHERE p.id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) { return rows.next() ? documentRow(rows, true) : null; }
        } catch (SQLException error) { throw failure(error); }
    }

    /** Читает exact committed version, не подменяя её текущим latest. */
    private Map<String, Object> version(String id) throws IOException {
        try (var query = context.connection().prepareStatement(LATEST.replace("s.id=p.latest_version AND s.page_id=p.id", "s.page_id=p.id") + " WHERE s.id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) { throw new IOException("Committed document version unavailable"); }
                return documentRow(rows, true);
            }
        } catch (SQLException error) { throw failure(error); }
    }

    /**
     * Читает bounded latest metadata, без открытия blob; callers держат context monitor.
     * @param jobId UUID плана
     * @param limit 1..100
     * @return metadata rows, отсортированные по реальному Instant
     * @throws IOException при invalid input/JDBC failure
     */
    public List<Map<String, Object>> list(String jobId, int limit) throws IOException {
        bound(limit);
        plans.plan(jobId);
        var result = new ArrayList<Map<String, Object>>();
        try (var query = context.connection().prepareStatement(LATEST + " WHERE p.job_id=? ORDER BY CAST(s.recorded_at AS TIMESTAMP(9) WITH TIME ZONE),p.id LIMIT ?")) {
            query.setString(1, jobId);
            query.setInt(2, limit);
            try (var rows = query.executeQuery()) { while (rows.next()) { result.add(documentRow(rows, false)); } }
            return result;
        } catch (SQLException error) { throw failure(error); }
    }

    /** Проекция text evidence с проверенным source только при explicit read. */
    private Map<String, Object> documentRow(ResultSet rows, boolean content) throws SQLException, IOException {
        var value = new LinkedHashMap<String, Object>();
        for (String key : List.of("page_id", "job_id", "canonical_url", "last_seen", "snapshot_id", "content_hash",
            "content_type", "document_format", "source_kind", "source_url", "fetched_at", "recorded_at", "title")) {
            value.put(key, rows.getString(key));
        }
        value.put("schema_version", 1);
        value.put("document_id", rows.getString("page_id"));
        value.put("url", rows.getString("canonical_url"));
        value.put("depth", rows.getInt("discovery_depth"));
        value.put("content_size", rows.getInt("content_size"));
        value.put("http_status", rows.getInt("http_status"));
        value.put("storage_location", context.objects().resolve(rows.getString("content_hash") + ".txt").toString());
        if (content) {
            var decoded = ArchiveTextDocuments.decode(rows.getString("canonical_url"), rows.getString("content_type"),
                blobs.read(rows.getString("content_hash"), rows.getInt("content_size")));
            value.putAll(decoded);
            value.put("links", ARCHIVE_STORE_JSON_SERIALIZER.readValue(rows.getString("links_json"), new TypeReference<List<Map<String, Object>>>() {}));
            var history = new ArrayList<Map<String, Object>>();
            try (var query = context.connection().prepareStatement("SELECT id,content_hash,document_format,recorded_at FROM archive_document_versions_v1 WHERE page_id=? ORDER BY CAST(recorded_at AS TIMESTAMP(9) WITH TIME ZONE),id")) {
                query.setString(1, rows.getString("page_id"));
                try (var versions = query.executeQuery()) {
                    while (versions.next()) { history.add(Map.of("snapshot_id", versions.getString(1), "content_hash", versions.getString(2), "document_format", versions.getString(3), "recorded_at", versions.getString(4))); }
                }
            }
            value.put("history", history);
        }
        return value;
    }

    /**
     * Ищет latest text versions; все фильтры применяются до per-source LIMIT.
     * @param query literal substring
     * @param jobId optional job filter
     * @param host optional normalized onion hostname
     * @param limit bound 1..100, проверенный query service
     * @return metadata; source bytes здесь не открываются
     * @throws IOException при JDBC/integrity metadata failure
     */
    List<Map<String, Object>> search(String query, String jobId, String host, int limit) throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        try (var statement = context.connection().prepareStatement(LATEST +
                " WHERE (LOCATE(LOWER(?),LOWER(s.title))>0 OR LOCATE(LOWER(?),LOWER(s.extracted_text))>0 OR LOCATE(LOWER(?),LOWER(p.canonical_url))>0)" +
                (jobId == null ? "" : " AND p.job_id=?") +
                (host == null ? "" : " AND (LOCATE(?,LOWER(p.canonical_url))=1 OR LOCATE(?,LOWER(p.canonical_url))=1)") +
                " ORDER BY CAST(s.recorded_at AS TIMESTAMP(9) WITH TIME ZONE) DESC,p.id LIMIT ?")) {
            statement.setString(1, query);
            statement.setString(2, query);
            statement.setString(3, query);
            int index = 4;
            if (jobId != null) { statement.setString(index++, jobId); }
            if (host != null) { statement.setString(index++, "http://" + host + "/");
            statement.setString(index++, "https://" + host + "/"); }
            statement.setInt(index, limit);
            try (var rows = statement.executeQuery()) { while (rows.next()) { result.add(documentRow(rows, false)); } }
            return result;
        } catch (SQLException error) { throw failure(error); }
    }
}
