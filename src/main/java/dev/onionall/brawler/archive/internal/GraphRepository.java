package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.net.URI;
import java.sql.*;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Исторический граф обнаруженных ссылок архивных snapshots.</b>
 *
 * <p>Сохраняет immutable edge observations для новых snapshots,
 * включая отклонённые и внешние targets. Повторное обнаружение
 * ссылок без создания нового snapshot не фиксируется.
 *
 * <p>При чтении динамически разрешает {@code target_page_id} по canonical URL
 * только внутри того же job. Не выполняет Frontier admission,
 * сетевые запросы или самостоятельное управление JDBC.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class GraphRepository {

    /** Общий координатор JDBC-доступа; соединение репозиторию не принадлежит. */
    private final ArchiveContext context;

    /** Проверяет существование job перед чтением его графа. */
    private final PlanRepository plans;

    /**
     * <b>Связывает graph reads/writes с тем же context.</b>
     * @param context единый владелец JDBC
     * @param plans проверка job identity
     */
    public GraphRepository(
            ArchiveContext context,
            PlanRepository plans
    ) {
        this.context = context;
        this.plans = plans;
    }

    /** Извлекает display host untrusted target; malformed URL даёт пустую строку. */
    private static String hostOf(String url) {
        try {
            return Objects.toString(URI.create(url).getHost(), "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * <b>Материализует исторические связи с текущим разрешением targets.</b>
     *
     * <p>Требует владения монитором {@link ArchiveContext}. {@code target_page_id}
     * вычисляется на момент запроса и не изменяет сохранённые observations.
     * Использует только доверенные SQL suffix из внутренних вызовов.
     *
     * @param suffix доверенный статический SQL-фрагмент WHERE/ORDER/LIMIT
     * @param id идентификатор snapshot или job для первого placeholder
     * @param limit ограничение результата для второго placeholder
     * @return материализованные связи с вычисленными {@code target_page_id}
     * @throws SQLException при ошибке JDBC
     * @throws IOException если контекст закрыт
     */
    List<Map<String, Object>> readLinks(
            String suffix,
            String id,
            int limit
    ) throws SQLException, IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        // Resolve against the same job at serializableRead time: a later archived target
        // becomes navigable without mutating immutable snapshot observations.
        String sql = "SELECT l.*,s.page_id source_page_id,p.job_id,t.id target_page_id FROM page_links l " +
            "JOIN snapshots s ON s.id=l.snapshot_id JOIN pages p ON p.id=s.page_id " +
            "LEFT JOIN pages t ON t.job_id=p.job_id AND t.canonical_url=l.target_url";
        try (PreparedStatement query = context.connection().prepareStatement(sql + suffix)) {
            query.setString(1, id);
            if (suffix.endsWith("LIMIT ?")) {
                query.setInt(2, limit);
            }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    for (String key : List.of(
                            "snapshot_id",
                            "source_page_id",
                            "job_id",
                            "source_url",
                            "target_url",
                            "disposition",
                            "discovered_at",
                            "discovery_time_basis",
                            "target_page_id"
                    )) {
                        edge.put(key, rows.getString(key));
                    }
                    edge.put("ordinal", rows.getInt("ordinal"));
                    edge.put("source_host", hostOf(rows.getString("source_url")));
                    edge.put("target_host", hostOf(rows.getString("target_url")));
                    result.add(edge);
                }
            }
        }
        return result;
    }

    /**
     * <b>Читает bounded historical directed edges одного job.</b>
     * <p>Сохраняет rejected/foreign targets; target_page разрешается внутри job.
     * @param jobId UUID плана
     * @param limit максимум 1..100 связей
     * @return {@code job_id}, {@code links} и {@code limit}
     * @throws IOException при invalid input или ошибке чтения
     */
    public Map<String, Object> links(
            String jobId,
            int limit
    ) throws IOException {
        bound(limit);
        plans.plan(jobId);
        try {
            return Map.of(
                    "job_id", jobId, "links",
                    readLinks(
                            " WHERE p.job_id=? ORDER BY CAST(l.discovered_at AS TIMESTAMP(9) WITH TIME ZONE),l.snapshot_id,l.ordinal LIMIT ?",
                            jobId,
                            limit
                    ),
                    "limit", limit);
        } catch (SQLException e) {
            throw failure(e);
        }
    }


    /**
     * <b>Сохраняет первые immutable edge observations нового snapshot.</b>
     *
     * <p>Требует context transaction/catalog_guard. Replay существующего snapshot
     * сюда не поступает; graph не превращается в журнал повторных discovery events.
     *
     * @param snapshotId идентификатор нового snapshot
     * @param safeLinks проверенные bounded observations
     * @throws SQLException при SQL-ошибке; откат выполняет context
     * @throws IOException при отсутствии ownership/open context
     */
    void recordFirstLinks(
            String snapshotId,
            List<Map<String, String>> safeLinks
    ) throws SQLException, IOException {
        context.requireTransaction();
        int ordinal = 0;
        try (PreparedStatement insert = context.connection().prepareStatement(
                "INSERT INTO page_links(snapshot_id,ordinal,source_url,target_url,disposition,discovered_at,discovery_time_basis) VALUES(?,?,?,?,?,?,?)"
        )) {
            for (Map<String, String> link : safeLinks) {
                insert.setString(1, snapshotId);
                insert.setInt(2, ordinal++);
                insert.setString(3, link.get("source_url"));
                insert.setString(4, link.get("target_url"));
                insert.setString(5, link.get("disposition"));
                insert.setString(6, link.get("discovered_at"));
                insert.setString(7, link.get("discovery_time_basis"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
