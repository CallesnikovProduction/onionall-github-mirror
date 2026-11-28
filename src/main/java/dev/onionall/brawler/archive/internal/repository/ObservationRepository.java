package dev.onionall.brawler.archive.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.onionall.brawler.archive.internal.ArchiveContext;

import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Admission и bounded status audit.</b>
 *
 * <p>Хранит наблюдения, не scheduling authority. Все операции требуют ownership context; SQL mutation выполняется общей транзакцией.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ObservationRepository {
    /** Private JSON marker различает новый admission call и status-only row без DDL. */
    private static final String ADMISSION_CLOCK_FIELD = "_archive_admission_observed_at_v1";
    private static final Set<String> STATUS_AUDIT_FIELDS = Set.of(
            "job_status", "status_transitions", "status_transitions_dropped"
    );
    private static final Set<String> MANAGED_FIELDS = Set.of(
            "job_status", "status_transitions", "status_transitions_dropped",
            "admission_observed_at", "admission_authority", ADMISSION_CLOCK_FIELD
    );
    private final ArchiveContext context;
    private final PlanRepository plans;

    /**
     * <b>Связывает admission/status observations с тем же catalog.</b>
     * @param context единый владелец JDBC
     * @param plans проверка specification/approval
     */
    public ObservationRepository(
            ArchiveContext context,
            PlanRepository plans
    ) {
        this.context = context;
        this.plans = plans;
    }

    /**
     * <b>Сохраняет cached admission observation без изменения specification.</b>
     * <p>Context сериализует mutation; Frontier остаётся authority. Matching plan_id
     * разрешён для существующих worker callers, conflicting authority fields отклоняются.
     * Managed audit/provenance fields допускаются только как точное эхо текущей
     * проекции для read-modify-write callers; их значения определяет repository.
     * @param id UUID подтверждённого плана
     * @param observation JSON object bounded после сериализации
     * @throws IOException при invalid observation, отсутствии approval или persistence failure
     */
    public void recordAdmission(
            String id,
            Map<String, Object> observation
    ) throws IOException {
        Map<String, Object> plan = plans.plan(id);
        if (!Boolean.TRUE.equals(plan.get("approved"))) {
            throw new IOException("Plan is not approved");
        }
        if (
                observation == null ||
                observation.keySet().stream().anyMatch(Objects::isNull)
        ) {
            throw new IOException("Require an observation object with non-null keys");
        }
        if (
                observation.containsKey("plan_id") &&
                !id.equals(observation.get("plan_id"))
        ) {
            throw new IOException("Admission plan mismatch");
        }
        if (observation.keySet().stream().anyMatch(key ->
                PLAN_AUTHORITY_FIELDS.contains(key) && !key.equals("plan_id"))) {
            throw new IOException("Admission observations cannot change plan authority");
        }
        if (observation.containsKey("session_id") && !id.equals(observation.get("session_id"))) {
            throw new IOException("Admission session mismatch");
        }

        writeObservation(id, observation, null);
    }

    /**
     * <b>Сохраняет bounded observational status audit.</b>
     * <p>Context transaction сохраняет до 32 transitions; не изменяет Frontier.
     * @param id UUID плана
     * @param status job_status_version 1 с matching plan_id
     * @throws IOException при неверном status или persistence failure
     */
    public void recordJobStatus(
            String id,
            Map<String, Object> status
    ) throws IOException {
        plans.plan(id);
        if (status == null || status.keySet().stream().anyMatch(Objects::isNull)) {
            throw new IOException("Require a job status object with non-null keys");
        }
        if (
                !Objects.equals(status.get("plan_id"), id) ||
                !Objects.equals(status.get("job_status_version"), 1)
        ) {
            throw new IOException("Invalid job status observation");
        }
        writeObservation(id, Map.of(), status);
    }

    /**
     * <b>Транзакционно обновляет admission observation и status audit.</b>
     *
     * <p>Admission update заменяет предыдущие admission-поля,
     * сохраняя существующий status audit. Status update сохраняет
     * admission-поля и добавляет transition при изменении
     * job_state, completion или state_basis.
     *
     * <p>История ограничена 32 transitions. Избыточные записи
     * удаляются с увеличением status_transitions_dropped.
     *
     * @param id идентификатор crawl-плана
     * @param update admission observation; используется при status == null
     * @param status новое status observation либо null
     * @throws IOException при нарушении контракта или ошибке записи
     */
    private void writeObservation(
            String id,
            Map<String, Object> update,
            Map<String, Object> status
    ) throws IOException {
        context.transaction(() -> {
            context.capacity();
            Map<String, Object> old = new LinkedHashMap<>(admissionObservation(id));
            if (status == null) {
                validateManagedEcho(update, old);
            }
            Object admissionClock = old.get("admission_observed_at");
            old.remove("admission_observed_at");
            old.remove("admission_authority");
            Map<String, Object> value = status == null ? new LinkedHashMap<>(update) : new LinkedHashMap<>(old);
            if (status == null) {
                MANAGED_FIELDS.forEach(value::remove);
                for (String key : STATUS_AUDIT_FIELDS) {
                    if (old.containsKey(key)) {
                        value.put(key, old.get(key));
                    }
                }
            } else {
                List<Object> history = new ArrayList<>();
                if (old.get("status_transitions") instanceof List<?> entries) {
                    history.addAll(entries);
                } else if (old.containsKey("status_transitions")) {
                    throw new IOException("Malformed status audit");
                }
                Map<?, ?> previous = old.get("job_status") instanceof Map<?, ?> m ? m : Map.of();
                if (
                        Stream
                                .of(
                                "job_state",
                                        "completion",
                                        "state_basis"
                                )
                                .anyMatch(k ->
                                        !Objects.equals(previous.get(k), status.get(k))
                                )
                ) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    for (String key : List.of(
                            "job_state",
                            "completion",
                            "state_basis",
                            "observed_at"
                    )) {
                        event.put(key, status.get(key));
                    }
                    history.add(event);
                }
                long dropped = old.get("status_transitions_dropped") instanceof Number n ? n.longValue() : 0;
                if (dropped < 0 || dropped > 1_000_000_000) {
                    throw new IOException("Invalid status audit bound");
                }
                while (history.size() > 32) {
                    history.removeFirst();
                    dropped++;
                }
                value.put("job_status", status);
                value.put("status_transitions", history);
                value.put("status_transitions_dropped", dropped);
            }
            String clock = status == null ? Instant.now().toString()
                : admissionClock instanceof String observed ? observed : null;
            if (clock != null) {
                value.put(ADMISSION_CLOCK_FIELD, clock);
            }
            String encoded = ARCHIVE_STORE_JSON_SERIALIZER.writeValueAsString(value);
            if (encoded.length() > 16384) {
                throw new IOException("Admission observation exceeds bound");
            }
            try (var st = context.connection().prepareStatement(
                    "MERGE INTO admission_observations(job_id,observation_json,observed_at) KEY(job_id) VALUES(?,?,?)"
            )) {
                st.setString(1, id);
                st.setString(2, encoded);
                st.setString(3, clock == null ? Instant.now().toString() : clock);
                st.executeUpdate();
            }

            return null;
        });
    }

    /**
     * Разрешает managed fields только как точное эхо сохранённой проекции.
     *
     * <p>Вызывается под catalog_guard до удаления computed metadata. Это сохраняет
     * совместимость read-modify-write и не позволяет подменить audit либо записать
     * устаревшую версию. Private marker никогда не принимается от callers.
     *
     * @param update входное admission observation
     * @param persisted актуальная проекция под общей transaction
     * @throws IOException при новом/изменённом managed поле или private marker
     */
    private static void validateManagedEcho(Map<String, Object> update, Map<String, Object> persisted)
            throws IOException {
        for (String key : update.keySet()) {
            if (ADMISSION_CLOCK_FIELD.equals(key) || (MANAGED_FIELDS.contains(key)
                    && (!persisted.containsKey(key) || !Objects.equals(update.get(key), persisted.get(key))))) {
                throw new IOException("Admission observations cannot change repository-managed fields");
            }
        }
    }

    /**
     * <b>Проверяет legacy launch evidence во всём каталоге.</b>
     * <p>Чтение под context ownership, без scheduler authority и migration.
     *
     * <p>Проверяет наличие legacy-признаков worker execution,
     * а не факт текущего запуска или существования живого worker.
     * @return найдено ли сохранённое execution evidence
     * @throws IOException при malformed observation или недоступном каталоге
     */
    public boolean hasExecutionHistory() throws IOException {
        try (
                var statement = context.connection().prepareStatement(
                        "SELECT observation_json FROM admission_observations"
                );
                var rows = statement.executeQuery()
        ) {
            while (rows.next()) {
                Map<?, ?> value = observationObject(rows.getString(1));
                if (
                        value.containsKey("worker_pid") ||
                        value.containsKey("worker_owner_pid") ||
                        value.containsKey("runtime") ||
                        "COMMITTED".equals(value.get("admission"))
                ) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IOException("Execution ownership history unavailable", e);
        }
    }

    /**
     * <b>Читает сохранённое admission-наблюдение.</b>
     *
     * <p>Добавляет admission clock/authority только при admission evidence.
     * Status-only row остаётся локальным status audit. Private JSON marker не
     * выдаётся callers; legacy строки определяются по наличию admission-полей.
     *
     * @param id идентификатор записи
     * @return сохранённое наблюдение либо пустая карта
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> admissionObservation(String id) throws IOException {
        identifier(id);
        try (PreparedStatement st = context.connection().prepareStatement(
                "SELECT observation_json,observed_at FROM admission_observations WHERE job_id=?"
        )) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                Map<String, Object> value = observationObject(rs.getString(1));
                String clock = admissionClock(value, rs.getString(2));
                if (clock != null) {
                    value.put("admission_observed_at", clock);
                    value.put("admission_authority", "URLFrontier");
                }
                return value;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * Выделяет admission evidence и удаляет внутренние/вычисляемые поля из проекции.
     * Legacy status-only JSON содержит только status audit; пустой legacy object
     * не позволяет доказать admission. Новые пустые admission calls имеют marker.
     * @param value материализованный JSON object; изменяется только локальная карта
     * @param legacyClock прежняя SQL отметка времени для legacy admission-полей
     * @return проверенный admission clock либо null при отсутствии evidence
     * @throws IOException при malformed private marker
     */
    private static String admissionClock(Map<String, Object> value, String legacyClock) throws IOException {
        boolean explicit = value.containsKey(ADMISSION_CLOCK_FIELD);
        Object marker = value.remove(ADMISSION_CLOCK_FIELD);
        value.remove("admission_observed_at");
        value.remove("admission_authority");
        if (!explicit) {
            return value.keySet().stream().anyMatch(key -> !STATUS_AUDIT_FIELDS.contains(key)) ? legacyClock : null;
        }
        if (!(marker instanceof String clock) || clock.length() > 40) {
            throw new IOException("Malformed admission provenance marker");
        }
        try {
            Instant.parse(clock);
            return clock;
        } catch (java.time.format.DateTimeParseException error) {
            throw new IOException("Malformed admission provenance marker");
        }
    }

    /**
     * Проверяет JSON object, включая legacy строки, до построения проекции.
     * @param json сохранённый JSON
     * @return новая карта observation
     * @throws IOException при malformed/null/non-object JSON
     */
    private static Map<String, Object> observationObject(String json) throws IOException {
        Map<String, Object> value = ARCHIVE_STORE_JSON_SERIALIZER.readValue(
                json,
                new TypeReference<>() {}
        );
        if (value == null) {
            throw new IOException("Malformed admission observation");
        }
        return value;
    }
}
