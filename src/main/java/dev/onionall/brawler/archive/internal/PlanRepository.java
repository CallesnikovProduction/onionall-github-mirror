package dev.onionall.brawler.archive.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Планы, approval и canonical scope.</b>
 *
 * <p>Не владеет JDBC-соединением. Чтения выполняются под монитором
 * {@link ArchiveContext#serializableRead}, изменения — через
 * {@link ArchiveContext#transaction}. Approval фиксируется только
 * в локальном каталоге и не означает admission в Frontier.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class PlanRepository {

    /** Общий координатор JDBC-доступа и транзакций; репозиторий не владеет его lifecycle. */
    private final ArchiveContext context;

    /**
     * <b>Создаёт адаптер планов; context сохраняет JDBC ownership.</b>
     * @param context единый владелец lifecycle и транзакций
     */
    public PlanRepository(ArchiveContext context) {
        this.context = context;
    }

    /**
     * <b>Сохраняет неподтверждённый план обхода.</b>
     *
     * <p>Проверяет same-origin seeds, глубину и бюджет; не резервирует URL в Frontier.
     *
     * @param seeds разрешённые начальные URL
     * @param depth глубина обнаружения
     * @param maxPages лимит страниц
     * @param provider атрибуция источника
     * @return карта сохранённого плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> createPlan(
        List<String> seeds,
        int depth,
        int maxPages,
        String provider
    ) throws IOException {
        return context.transaction(() ->
                createPlanInternal(seeds, depth, maxPages, provider)
        );
    }

    /** Проверяет specification и записывает план в context transaction. */
    private Map<String, Object> createPlanInternal(
            List<String> seeds,
            int depth,
            int maxPages,
            String provider
    ) throws IOException {
        context.capacity();
        if (
                seeds == null ||
                seeds.isEmpty() ||
                seeds.size() > 100 ||
                depth < 0 ||
                depth > 5 ||
                maxPages < 1 ||
                maxPages > 100
        ) {
            throw new IOException("Require1..100 seeds/pages and depth0..5");
        }

        provider = bounded(
                provider == null
                        ? "user"
                        : provider,
                256,
                "provider attribution"
        );

        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        String allowed = null;

        // Canonical scope is defined by ArchiveSupport.normalize/origin.
        // The first normalized seed establishes the scope for all others.
        for (String seed : seeds) {
            String normalized = normalize(seed, allowed);
            if (allowed == null) {
                allowed = origin(normalized);
            }
            canonical.add(normalized);
        }

        if (canonical.size() > maxPages) {
            throw new IOException("Seeds exceed page budget");
        }

        String id = UUID.randomUUID().toString();

        try (PreparedStatement insert = context.connection().prepareStatement(
                "INSERT INTO crawl_plans(id,seeds_json,origin,max_depth,max_pages,provider,created_at) VALUES(?,?,?,?,?,?,?)"
        )) {
            insert.setString(1, id);
            insert.setString(2, ARCHIVE_STORE_JSON_SERIALIZER.writeValueAsString(canonical));
            insert.setString(3, allowed);
            insert.setInt(4, depth);
            insert.setInt(5, maxPages);
            insert.setString(6, provider);
            insert.setString(7, Instant.now().toString());
            insert.executeUpdate();
            return plan(id);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Читает bounded keyset список планов.</b>
     * <p>Live membership, не frozen snapshot и не scheduler queue.
     * @param after предыдущий plan_id либо null
     * @param limit максимум 1..100 планов
     * @return plans, next_cursor и признак truncated
     * @throws IOException при invalid cursor/limit или ошибке чтения
     */
    public Map<String, Object> plans(
            String after,
            int limit
    ) throws IOException {
        bound(limit);
        if (after != null) {
            identifier(after);
        }
        List<String> ids = new ArrayList<>();
        try (var query = context.connection().prepareStatement(
                "SELECT id FROM crawl_plans WHERE id>? ORDER BY id LIMIT ?"
        )) {
            query.setString(1, after == null ? "" : after);
            query.setInt(2, limit + 1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw failure(e);
        }
        boolean more = ids.size() > limit;
        if (more) {
            ids.removeLast();
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (String id : ids) {
            plans.add(plan(id));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan_list_version", 1);
        result.put("plans", plans);
        result.put("limit", limit);
        result.put("truncated", more);
        result.put("next_cursor", more ? ids.getLast() : null);
        result.put("order", "plan_id");
        result.put("membership_snapshot", false);
        return result;
    }

    /**
     * <b>Читает план из каталога.</b>
     *
     * <p>Проверяет идентификатор; неизвестный план вызывает IOException.
     * <p>Поля state и admission отражают только каталоговый план:
     * APPROVED не означает запуск обхода, а UNOBSERVED не является
     * проверкой текущего состояния Frontier.
     * @param id идентификатор записи
     * @return карта плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> plan(String id) throws IOException {
        identifier(id);
        try (PreparedStatement query = context.connection().prepareStatement(
                "SELECT * FROM crawl_plans WHERE id=?"
        )) {
            query.setString(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Unknown crawl plan");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("plan_id", id);
                result.put("job_id", id);
                result.put("seeds", ARCHIVE_STORE_JSON_SERIALIZER.readValue(rs.getString("seeds_json"), new TypeReference<List<String>>() {
                }));
                result.put("origin", rs.getString("origin"));
                result.put("scope", "same-host");
                result.put("depth", rs.getInt("max_depth"));
                result.put("max_pages", rs.getInt("max_pages"));
                result.put("provider", rs.getString("provider"));
                result.put("approved", rs.getBoolean("approved"));

                // Catalog-only projection: approval is not Frontier admission.
                // Runtime and authoritative observations are reconciled elsewhere.
                result.put("state", rs.getBoolean("approved") ? "APPROVED" : "DRAFT");
                result.put("admission", "UNOBSERVED");
                result.put("admission_basis", "CATALOG_PLAN_ONLY");
                result.put("created_at", rs.getString("created_at"));
                return result;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Сохраняет явное подтверждение плана.</b>
     *
     * <p>Изменяет approval в H2 без сетевого admission.
     *
     * @param id идентификатор записи
     * @return обновлённая карта плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> approvePlan(String id) throws IOException {
        return context.transaction(() -> approvePlanInternal(id));
    }

    /** Устанавливает approval внутри общей transaction без изменения specification. */
    private Map<String, Object> approvePlanInternal(String id) throws IOException {
        context.capacity();
        plan(id);
        try (PreparedStatement update = context.connection().prepareStatement("UPDATE crawl_plans SET approved=TRUE WHERE id=?")) {
            update.setString(1, id);
            update.executeUpdate();
            return plan(id);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /**
     * <b>Читает план с обязательным approval.</b>
     *
     * <p>Отсутствие явного подтверждения запрещает сохранение HTML.
     *
     * @param id идентификатор записи
     * @return подтверждённый план
     * @throws IOException при ошибке операции или нарушении контракта
     */
    Map<String, Object> approved(String id) throws IOException {
        Map<String, Object> plan = plan(id);
        if (!Boolean.TRUE.equals(plan.get("approved"))) {
            throw new IOException("Crawl plan requires explicit approval");
        }
        return plan;
    }
}
