package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.tor.TorPolicy;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Каталожные проекции, поиск и экспорт.</b>
 *
 * <p>Комбинирует serializableRead-side данные под ownership context. Не задаёт scheduling policy и не управляет транзакциями.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ArchiveQueryService {
    private final ArchiveContext context;
    private final PlanRepository plans;
    private final ObservationRepository observations;
    private final SnapshotRepository snapshots;
    private final GraphRepository graph;
    private final DocumentRepository documents;

    /**
     * <b>Собирает serializableRead-side проекции одного catalog.</b>
     * @param context единый владелец JDBC
     * @param plans источник specification
     * @param observations источник cached status/admission
     * @param snapshots источник page metadata
     * @param graph источник связей
     */
    public ArchiveQueryService(ArchiveContext context, PlanRepository plans, ObservationRepository observations,
            SnapshotRepository snapshots, GraphRepository graph) {
        this.context = context;
        this.plans = plans;
        this.observations = observations;
        this.snapshots = snapshots;
        this.graph = graph;
        this.documents = new DocumentRepository(context, plans);
    }

    /**
     * <b>Формирует сводную каталожную проекцию задания.</b>
     *
     * <p>Объединяет authoritative plan specification, cached observations,
     * счётчики страниц и snapshots, агрегаты ошибок и последнее failure
     * observation. Не запрашивает текущее состояние Frontier.
     *
     * <p>Failure aggregates не являются счётчиком fetch attempts;
     * cached observations не доказывают текущее execution state.
     *
     * @param id идентификатор crawl-плана
     * @return сводная карта задания
     * @throws IOException при неизвестном плане или ошибке чтения
     */
    public Map<String, Object> job(String id) throws IOException {
        return context.consistentRead(() -> jobInternal(id));
    }

    /** Строит проекцию под общим catalog_guard без nested transaction. */
    private Map<String, Object> jobInternal(String id) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>(plans.plan(id));
        Set<String> specification = new HashSet<>(result.keySet());
        specification.removeAll(Set.of("state", "admission", "admission_basis"));
        // Protect actual plan keys too: future specification fields remain authoritative.
        observations.admissionObservation(id).forEach((key, value) -> {
            if (!PLAN_AUTHORITY_FIELDS.contains(key) && !specification.contains(key)) {
                result.put(key, value);
            }
        });
        try {
            result.put("archived_pages", archivedUrlCount(context.connection(), id));
            result.put("html_pages", count(context.connection(), "SELECT COUNT(*) FROM pages WHERE job_id=?", id));
            result.put("archived_documents", count(context.connection(), "SELECT COUNT(*) FROM archive_document_pages_v1 WHERE job_id=?", id));
            result.put("document_versions", count(context.connection(), "SELECT COUNT(*) FROM archive_document_versions_v1 v JOIN archive_document_pages_v1 p ON p.id=v.page_id WHERE p.job_id=?", id));
            result.put("snapshots",
                count(context.connection(), "SELECT COUNT(*) FROM snapshots s JOIN pages p ON p.id=s.page_id WHERE p.job_id=?", id));
            result.put("failures", count(context.connection(), "SELECT COUNT(*) FROM failures WHERE job_id=?", id));
            List<Map<String, Object>> failures = new ArrayList<>();
            try (PreparedStatement query = context.connection().prepareStatement("SELECT id,canonical_url,category,occurred_at FROM failures WHERE job_id=? ORDER BY CAST(occurred_at AS TIMESTAMP(9) WITH TIME ZONE) DESC,id LIMIT 100")) {
                query.setString(1, id);
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        failures.add(
                                Map.of(
                                        "failure_id", rs.getString(1),
                                        "url", rs.getString(2),
                                        "category", rs.getString(3),
                                        "occurred_at", rs.getString(4)
                                )
                        );
                    }
                }
            }
            result.put("failure_records", failures);
            result.put("failure_count_basis", "LEGACY_UNIQUE_URL_CATEGORY");
            result.put("failure_observation_count",
                count(context.connection(), "SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=?", id));
            result.put("fetch_attempt_count", null);
            try (var query = context.connection().prepareStatement("SELECT * FROM failure_observations_v1 WHERE job_id=? ORDER BY observation_seq DESC LIMIT 1")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    result.put("latest_failure_observation", row.next() ? FailureRepository.failureObservationRow(row) : null);
                }
            }
            result.put("scheduling_owner", "URLFrontier");
            result.put("archive_owner", "local catalog");
            return result;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Ищет по URL, title и extracted_text каталога.</b>
     *
     * <p>Использует literal LOCATE и bounded результат; не вызывает search provider.
     *
     * @param query поисковая строка
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта результатов
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> search(
            String query,
            String jobId,
            int limit
    ) throws IOException {
        return search(query, jobId, null, limit);
    }

    /**
     * <b>Ищет по последним snapshots локального каталога.</b>
     *
     * <p>Выполняет literal substring search по URL, title и extracted_text.
     * Поиск ограничен latest snapshot каждой страницы; исторические версии
     * не участвуют. SQL-фильтры применяются до LIMIT.
     *
     * <p>LIMIT ограничивает размер ответа, но не стоимость сканирования
     * каталога. Метод не обращается к внешнему search provider.
     *
     * @param query непустая поисковая подстрока
     * @param jobId идентификатор задания либо null
     * @param host точный onion v3 hostname либо null
     * @param limit максимум 1..100 результатов
     * @return результаты поиска по локальному каталогу
     * @throws IOException при некорректных фильтрах или ошибке чтения
     */
    public Map<String, Object> search(
            String query,
            String jobId,
            String host,
            int limit
    ) throws IOException {
        return context.consistentRead(() -> searchInternal(query, jobId, host, limit));
    }

    /** Собирает bounded HTML/text search под единым cooperating-writer guard. */
    private Map<String, Object> searchInternal(String query, String jobId, String host, int limit) throws IOException {
        bound(limit);
        bounded(query, 256, "search query");
        if (query.isBlank()) {
            throw new IOException("Search query is empty");
        }
        if (jobId != null) {
            plans.plan(jobId);
        }
        if (host != null) {
            host = host.toLowerCase(Locale.ROOT);
            if (!TorPolicy.validOnion(host)) {
                throw new IOException("Require a valid onion host filter");
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();

        // Cast legacy variable-precision Instant strings at read time; no data migration.
        try (PreparedStatement statement = context.connection().prepareStatement(
                SnapshotRepository.LATEST +
                        " WHERE (LOCATE(LOWER(?),LOWER(s.title))>0 OR LOCATE(LOWER(?),LOWER(s.extracted_text))>0" +
                        " OR LOCATE(LOWER(?),LOWER(p.canonical_url))>0)" +
                        (jobId == null ? "" : " AND p.job_id=?") +
                        (host == null ? "" : " AND (LOCATE(?,LOWER(p.canonical_url))=1 OR LOCATE(?,LOWER(p.canonical_url))=1)") +
                        " ORDER BY CAST(s.recorded_at AS TIMESTAMP(9) WITH TIME ZONE) DESC,p.id LIMIT ?"
        )) {
            statement.setString(1, query);
            statement.setString(2, query);
            statement.setString(3, query);
            int index = 4;
            if (jobId != null) {
                statement.setString(index++, jobId);
            }
            if (host != null) {
                statement.setString(index++, "http://" + host + "/");
                statement.setString(index++, "https://" + host + "/");
            }
            statement.setInt(index, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(snapshots.snapshotRow(rs));
                }
            }
            result.addAll(documents.search(query, jobId, host, limit));
            result.sort(Comparator.<Map<String, Object>, java.time.Instant>comparing(
                row -> java.time.Instant.parse((String) row.get("recorded_at"))).reversed()
                .thenComparing(row -> (String) row.get("page_id")));
            if (result.size() > limit) { result.subList(limit, result.size()).clear(); }
            return Map.of(
                    "query", query,
                    "results", result,
                    "limit", limit,
                    "provider", "local-archive"
            );
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Формирует bounded экспорт локальных метаданных.</b>
     *
     * <p>Читает страницы и граф из каталога; файл публикует вызывающий код.
     *
     * <p>Удерживает общий catalog_guard на протяжении всех component reads.
     * Сотрудничающие Archive writers не могут изменить rows между job/pages/links;
     * прямые SQL writers, обходящие guard, не входят в поддерживаемую модель.
     *
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта экспортируемых данных
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> exportSummary(
            String jobId,
            int limit
    ) throws IOException {
        return context.consistentRead(() -> Map.of(
                "job", jobInternal(jobId),
                "pages", snapshots.pages(jobId, limit).get("pages"),
                "links", graph.links(jobId, limit).get("links"),
                "documents", documents.list(jobId, limit),
                "limit", limit
        ));
    }

}
