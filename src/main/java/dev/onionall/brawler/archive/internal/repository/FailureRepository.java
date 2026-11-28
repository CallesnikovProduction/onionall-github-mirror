package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.FailureObservation;
import dev.onionall.brawler.archive.internal.ArchiveContext;

import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Агрегаты ошибок и append-only история failure observations.</b>
 *
 * <p>Агрегат идентифицируется комбинацией job, canonical URL и category.
 * Повторное наблюдение переиспользует агрегат, но создаёт новую
 * immutable observation в общей catalog transaction.
 *
 * <p>История использует bounded keyset pagination с фиксированным
 * through_sequence. Observation sequence не является счётчиком
 * сетевых попыток или состоянием Frontier scheduler.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class FailureRepository {

    /** Общий владелец JDBC-соединения и транзакционного протокола. */
    private final ArchiveContext context;

    /** Проверяет approval и предоставляет canonical scope плана. */
    private final PlanRepository plans;

    /**
     * <b>Связывает aggregates и observations в одной catalog transaction.</b>
     * @param context единый владелец JDBC
     * @param plans проверка approval/scope
     */
    public FailureRepository(
            ArchiveContext context,
            PlanRepository plans
    ) {
        this.context = context;
        this.plans = plans;
    }

    /**
     * <b>Сохраняет агрегат ошибки и структурированное наблюдение.</b>
     *
     * <p>Транзакция ограничена catalog_guard; наблюдение не равно отдельной сетевой попытке.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param category нормализованная категория ошибки
     * @return идентификатор агрегата ошибки, job_id, URL и категория
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> recordFailure(
        String jobId,
        String url,
        String category
    ) throws IOException {
        return recordFailure(jobId, url, category, FailureObservation.unknown());
    }

    /**
     * <b>Сохраняет агрегат ошибки и структурированное наблюдение.</b>
     *
     * <p>Транзакция ограничена catalog_guard; наблюдение не равно отдельной сетевой попытке.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param category нормализованная категория ошибки
     * @param observation структурированное наблюдение
     * @return идентификатор агрегата ошибки, job_id, URL и категория
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> recordFailure(
        String jobId,
        String url,
        String category,
        FailureObservation observation
    ) throws IOException {
        return context.transaction(() ->
                recordFailureInternal(jobId, url, category, observation)
        );
    }

    /**
     * <b>Атомарно сохраняет failure aggregate и новое observation.</b>
     *
     * <p>Переиспользует агрегат для той же комбинации job, canonical URL
     * и category. Каждое успешное обращение добавляет observation;
     * rollback отменяет обе SQL-операции.
     *
     * <p>Требует активной ArchiveContext transaction с catalog_guard.
     */
    private Map<String, Object> recordFailureInternal(
            String jobId,
            String url,
            String category,
            FailureObservation observation
    ) throws IOException {
        context.capacity();
        Map<String, Object> plan = plans.approved(jobId);
        url = normalize(url, (String) plan.get("origin"));
        if (category == null || !category.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IOException("Require bounded failure category");
        }
        if (observation == null) {
            throw new IOException("Require structured failure observation");
        }
        try {
            String id = null;
            try (PreparedStatement query = context.connection().prepareStatement("SELECT id FROM failures WHERE job_id=? AND canonical_url=? AND category=?")) {
                query.setString(1, jobId);
                query.setString(2, url);
                query.setString(3, category);
                try (ResultSet rs = query.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getString(1);
                    }
                }
            }
            if (id == null) {
                if (count(context.connection(), "SELECT COUNT(*) FROM failures WHERE job_id=?", jobId) >= 1000) {
                    throw new IOException("Job failure record limit1000");
                }
                id = UUID.randomUUID().toString();

                try (PreparedStatement insert = context.connection().prepareStatement(
                        "INSERT INTO failures(id,job_id,canonical_url,category,occurred_at) VALUES(?,?,?,?,?)"
                )) {
                    insert.setString(1, id);
                    insert.setString(2, jobId);
                    insert.setString(3, url);
                    insert.setString(4, category);
                    insert.setString(5, Instant.now().toString());
                    insert.executeUpdate();
                }
            }

            // Saturation is a persistence failure: the whole mutation rolls back.
            // Archive status updaters propagate this IOException before Frontier store;
            // do not silently discard evidence or change scheduler retry policy here.
            // TODO(FAIL-002-PRODUCER, owner: worker/application lifecycle): define an
            // explicit archive-capacity stop/recovery outcome upstream. Archive-only
            // code cannot acknowledge/dismiss the tuple without changing that contract.
            if (count(context.connection(), "SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=?", jobId) >= 1000) {
                throw new IOException("Job failure observation limit1000; no automatic deletion");
            }
            try (var insert = context.connection().prepareStatement("INSERT INTO failure_observations_v1(job_id,failure_id,canonical_url,category,observed_at,phase,source,http_status,retryable,terminal_intent,next_due_epoch,transport_category) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, jobId);
                insert.setString(2, id);
                insert.setString(3, url);
                insert.setString(4, category);
                insert.setString(5, Instant.now().toString());
                insert.setString(6, observation.phase().name());
                insert.setString(7, observation.source().name());
                insert.setObject(8, observation.httpStatus(), Types.INTEGER);
                insert.setObject(9, observation.retryable(), Types.BOOLEAN);
                insert.setObject(10, observation.terminal(), Types.BOOLEAN);
                insert.setObject(11, observation.nextDueEpoch(), Types.BIGINT);
                insert.setString(12, observation.transport().name());
                insert.executeUpdate();
            }
            return Map.of("failure_id", id, "job_id", jobId, "url", url, "category", category);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Читает immutable failure observations с фиксированным through_sequence.</b>
     *
     * <p>through_sequence фиксирует верхнюю границу observation history
     * между страницами. legacy_aggregate_count отражает состояние
     * агрегатов на момент конкретного чтения и не закрепляется cursor.
     * <p>Под context ownership; sequence не является fetch counter или scheduler queue.
     * @param jobId UUID плана
     * @param cursor job-scoped continuation либо null
     * @param limit максимум 1..100 observations
     * @return rows, nullable next_cursor и исторические counts
     * @throws IOException при invalid cursor/limit или ошибке чтения
     */
    public Map<String, Object> failureHistory(
            String jobId,
            String cursor,
            int limit
    ) throws IOException {
        return context.consistentRead(() -> failureHistoryInternal(jobId, cursor, limit));
    }

    /** Читает одну согласованную страницу под catalog_guard; cursor фиксирует только observations. */
    private Map<String, Object> failureHistoryInternal(String jobId, String cursor, int limit) throws IOException {
        plans.plan(jobId);
        bound(limit);
        try {
            long maximum;
            try (var query = context.connection().prepareStatement("SELECT COALESCE(MAX(observation_seq),0) FROM failure_observations_v1 WHERE job_id=?")) {
                query.setString(1, jobId);
                try (var row = query.executeQuery()) {
                    row.next();
                    maximum = row.getLong(1);
                }
            }
            long through = maximum, after = 0;
            if (cursor != null) {
                bounded(cursor, 256, "failure history cursor");
                try {
                    String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8).split(":", -1);
                    if (parts.length != 4 || !parts[0].equals("1") || !parts[1].equals(jobId) ||
                        !parts[2].matches("[0-9]{1,19}") || !parts[3].matches("[0-9]{1,19}")) {
                        throw new IllegalArgumentException();
                    }
                    through = Long.parseLong(parts[2]);
                    after = Long.parseLong(parts[3]);
                    if (through > maximum || after < 1 || after >= through) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid failure history cursor");
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            long last = after;
            try (var query = context.connection().prepareStatement("SELECT * FROM failure_observations_v1 WHERE job_id=? AND observation_seq>? AND observation_seq<=? ORDER BY observation_seq LIMIT ?")) {
                query.setString(1, jobId);
                query.setLong(2, after);
                query.setLong(3, through);
                query.setInt(4, limit);
                try (var rs = query.executeQuery()) {
                    while (rs.next()) {
                        last = rs.getLong("observation_seq");
                        rows.add(failureObservationRow(rs));
                    }
                }
            }
            long count;
            try (var query = context.connection().prepareStatement("SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=? AND observation_seq<=?")) {
                query.setString(1, jobId);
                query.setLong(2, through);
                try (var row = query.executeQuery()) {
                    row.next();
                    count = row.getLong(1);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("history_version", 1);
            result.put("plan_id", jobId);
            result.put("rows", rows);
            result.put("observation_count", count);
            result.put("observation_count_basis", "THROUGH_SEQUENCE");
            result.put("fetch_attempt_count", null);
            result.put("legacy_aggregate_count", count(context.connection(), "SELECT COUNT(*) FROM failures WHERE job_id=?", jobId));
            result.put("legacy_aggregates_are_attempts", false);
            result.put("legacy_aggregate_count_basis", "LIVE_AT_READ");
            result.put("through_sequence", through);
            result.put("limit", limit);
            result.put("next_cursor", null);
            result.put("truncated", last < through);
            if (last < through) {
                if (rows.isEmpty()) {
                    throw new IOException("Failure history cursor points to unavailable rows");
                }
                result.put("next_cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + jobId +
                    ":" + through + ":" + last).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return result;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * Проецирует immutable observation, сохраняя nullable intent/attempt uncertainty.
     * @param rs текущая observation row под ownership вызывающего
     * @return JSON-compatible карта без scheduler authority
     * @throws SQLException при ошибке чтения column
     */
    static Map<String, Object> failureObservationRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observation_id", rs.getLong("observation_seq"));
        for (String key : List.of(
                "job_id",
                "failure_id",
                "category",
                "observed_at",
                "phase",
                "source",
                "transport_category"
        )) {
            row.put(key, rs.getString(key));
        }
        row.put("url", rs.getString("canonical_url"));
        row.put("http_status", rs.getObject("http_status"));
        row.put("retryable", rs.getObject("retryable"));
        row.put("terminal", rs.getObject("terminal_intent"));
        row.put("next_due_epoch", rs.getObject("next_due_epoch"));
        row.put("applied_to_frontier", null);
        return row;
    }

}
