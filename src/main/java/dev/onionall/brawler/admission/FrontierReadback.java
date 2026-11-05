package dev.onionall.brawler.admission;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * <b>Строгая проверка версионированных наблюдений Frontier.</b>
 *
 * <p>Неизвестные исходы не интерпретируются как успешный пустой результат.
 */
public final class FrontierReadback {

    private FrontierReadback() {
    }

    public enum State {

        PENDING, HELD, SCHEDULED_WAIT, UNSCHEDULED, UNKNOWN
    }

    public record Row(
        String reservationId,
        int ordinal,
        String url,
        String sourceUrl,
        int depth,
        State state,
        Long nextDueEpoch,
        Long leaseUntilEpoch
    ) {
    }

    public record Page(
        String sessionId,
        Instant readbackAt,
        int throughOrdinal,
        List<Row> rows,
        String nextCursor
    ) {
    }

    public record Counts(
        String sessionId,
        Instant readbackAt,
        int reserved,
        int pending,
        int held,
        int scheduledWait,
        int unscheduled,
        int unknown,
        Integer done,
        Integer success,
        Integer terminal,
        Integer retryWait,
        Instant nextAttemptAt
    ) {
    }

    /**
     * <b>Сверяет серверную спецификацию с утверждённым планом.</b>
     *
     * <p>Проверяет идентичность сессии, scope, seeds, лимиты и approval.
     *
     * @param session ответ Frontier
     * @param plan локальный утверждённый план
     * @throws IOException если спецификация отличается от плана
     */
    public static void verifySession(Map<String, Object> session, Map<String, Object> plan) throws IOException {
        if (!(session.get("spec") instanceof Map<?, ?> spec) || !Objects.equals(spec.get("session_id"),
            plan.get("plan_id")) || !Objects.equals(spec.get("origin"), plan.get("origin")) ||
            !(spec.get("seeds") instanceof List<?> actualSeeds) ||
            !(plan.get("seeds") instanceof List<?> expectedSeeds) || !new HashSet<>(actualSeeds)
            .equals(new HashSet<>(expectedSeeds)) || !(spec.get("max_pages") instanceof Number pages) ||
            pages.intValue() != ((Number) plan.get("max_pages")).intValue() ||
            !(spec.get("max_depth") instanceof Number depth) || depth.intValue() != ((Number) plan.get("depth"))
            .intValue() || !Boolean.TRUE.equals(spec.get("approved"))) {
            throw new IOException("Authoritative session differs from approved plan");
        }
    }

    /**
     * <b>Проверяет страницу списка резервирований.</b>
     *
     * <p>Сверяет envelope, cursor, ordinal и scheduler-поля; некорректный ответ не считается пустой страницей.
     *
     * @param reply ответ Frontier
     * @param session идентификатор или наблюдение сессии Frontier
     * @param offeredCursor предъявленный курсор
     * @param limit максимальный размер страницы
     * @return проверенная страница
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static Page page(Map<String, Object> reply, String session, String offeredCursor, int limit) throws IOException {
        try {
            Instant at = envelope(reply, session, "STATE_LIST");
            int through = number(reply, "through_ordinal", 0, 100);
            if (!(reply.get("rows") instanceof List<?> items) || items.size() > limit) {
                throw new IllegalArgumentException();
            }
            List<Row> rows = new ArrayList<>();
            int previous = 0;
            if (offeredCursor != null) {
                String[] cursor = new String(Base64.getUrlDecoder().decode(offeredCursor),
                    java.nio.charset.StandardCharsets.UTF_8).split(":", -1);
                if (cursor.length != 4 || !cursor[0].equals("1") || !cursor[1].equals(session) ||
                    !cursor[2].equals(Integer.toString(through)) || !cursor[3].matches("[0-9]{1,3}")) {
                    throw new IllegalArgumentException();
                }
                previous = Integer.parseInt(cursor[3]);
                if (previous < 1 || previous >= through) {
                    throw new IllegalArgumentException();
                }
            }
            for (Object item : items) {
                Map<String, Object> row = object(item);
                int ordinal = number(row, "ordinal", 1, through);
                if (ordinal != previous + 1) {
                    throw new IllegalArgumentException();
                }
                previous = ordinal;
                String reservation = text(row, "reservation_id", 36);
                if (!UUID.fromString(reservation).toString().equals(reservation)) {
                    throw new IllegalArgumentException();
                }
                if (!session.equals(text(row, "session_id", 64))) {
                    throw new IllegalArgumentException();
                }
                Map<String, Object> scheduler = object(row.get("scheduler"));
                State state = State.valueOf(text(scheduler, "state", 32));
                if (!scheduler.containsKey("next_due_epoch")) {
                    throw new IllegalArgumentException();
                }
                Object value = scheduler.get("next_due_epoch");
                Long due = value == null ? null : integer(value, 0, Long.MAX_VALUE);
                if ((state == State.UNKNOWN || state == State.UNSCHEDULED) != (due == null)) {
                    throw new IllegalArgumentException();
                }
                if (!scheduler.containsKey("lease_until_epoch")) {
                    throw new IllegalArgumentException();
                }
                Long lease = scheduler.get("lease_until_epoch") == null ? null :
                    integer(scheduler.get("lease_until_epoch"), 0, Long.MAX_VALUE);
                if ((state == State.HELD) != (lease != null)) {
                    throw new IllegalArgumentException();
                }
                rows.add(new Row(reservation, ordinal, text(row, "url", 2048), text(row, "source_url", 2048),
                    number(row, "depth", 0, 6), state, due, lease));
            }
            if (!reply.containsKey("next_cursor")) {
                throw new IllegalArgumentException();
            }
            String cursor = reply.get("next_cursor") == null ? null : text(reply, "next_cursor", 256);
            if (cursor != null && (cursor.isEmpty() || rows.isEmpty() || previous >= through)) {
                throw new IllegalArgumentException();
            }
            if (cursor != null && !cursor.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" +
                session + ":" + through + ":" + previous).getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException();
            }
            if (cursor == null && previous != through) {
                throw new IllegalArgumentException();
            }
            return new Page(session, at, through, List.copyOf(rows), cursor);
        } catch (RuntimeException e) {
            throw new IOException("Malformed Frontier list readback", e);
        }
    }

    /**
     * <b>Проверяет серверные счётчики.</b>
     *
     * <p>Сверяет версию, идентичность сессии и согласованность значений.
     *
     * @param reply ответ Frontier
     * @param session идентификатор или наблюдение сессии Frontier
     * @return проверенное наблюдение счётчиков
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static Counts counts(Map<String, Object> reply, String session) throws IOException {
        try {
            Instant at = envelope(reply, session, "STATE_COUNTS");
            Map<String, Object> c = object(reply.get("counts"));
            int reserved = number(c, "reserved", 0, 100), pending = number(c, "pending", 0, 100);
            int held = number(c, "held", 0, 100), wait = number(c, "scheduled_wait", 0, 100);
            int unscheduled = number(c, "unscheduled", 0, 100), unknown = number(c, "unknown", 0, 100);
            if (reserved != pending + held + wait + unscheduled + unknown) {
                throw new IllegalArgumentException();
            }
            for (String key : List.of("done", "success", "terminal", "retry_wait")) {
                if (!c.containsKey(key) || c.get(key) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (!reply.containsKey("next_attempt_at")) {
                throw new IllegalArgumentException();
            }
            Instant next = reply.get("next_attempt_at") == null ? null : Instant.parse(text(reply,
                "next_attempt_at", 64));
            if ((pending + held + wait == 0) != (next == null)) {
                throw new IllegalArgumentException();
            }
            return new Counts(session, at, reserved, pending, held, wait, unscheduled, unknown, null, null, null,
                null, next);
        } catch (RuntimeException e) {
            throw new IOException("Malformed Frontier counts readback", e);
        }
    }

    /**
     * <b>Проверяет общий envelope ответа Frontier.</b>
     *
     * <p>Требует ожидаемые outcome, версию и session_id.
     *
     * @param reply ответ Frontier
     * @param session идентификатор или наблюдение сессии Frontier
     * @param outcome ожидаемый исход операции
     * @return время readback
     */
    private static Instant envelope(Map<String, Object> reply, String session, String outcome) {
        if (!outcome.equals(reply.get("outcome")) || !session.equals(reply.get("session_id")) || number(reply,
            "state_version", 1, 1) != 1) {
            throw new IllegalArgumentException();
        }
        return Instant.parse(text(reply, "readback_at", 64));
    }

    /**
     * <b>Проверяет объект JSON.</b>
     *
     * <p>Отклоняет значение другого типа.
     *
     * @param value проверяемые или публикуемые данные
     * @return карта объекта
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> m) || m.keySet().stream().anyMatch(k -> !(k instanceof String))) {
            throw new IllegalArgumentException();
        }
        return (Map<String, Object>) m;
    }

    /**
     * <b>Проверяет bounded строку ответа.</b>
     *
     * <p>Не приводит произвольные JSON-типы к строке.
     *
     * @param value проверяемые или публикуемые данные
     * @param key имя поля
     * @param max верхняя граница
     * @return проверенная строка
     */
    private static String text(Map<String, Object> value, String key, int max) {
        if (!(value.get(key) instanceof String text) || text.length() > max || text.indexOf(0) >= 0) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    /**
     * <b>Проверяет целое число в границах.</b>
     *
     * <p>Не принимает дробное значение или значение вне диапазона.
     *
     * @param value проверяемые или публикуемые данные
     * @param min нижняя граница
     * @param max верхняя граница
     * @return проверенное число
     */
    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException();
        }
        long n = ((Number) value).longValue();
        if (n < min || n > max) {
            throw new IllegalArgumentException();
        }
        return n;
    }

    /**
     * <b>Извлекает bounded целое поле ответа.</b>
     *
     * <p>Использует строгую проверку числового типа.
     *
     * @param value проверяемые или публикуемые данные
     * @param key имя поля
     * @param min нижняя граница
     * @param max верхняя граница
     * @return значение поля
     */
    private static int number(Map<String, Object> value, String key, int min, int max) {
        return (int) integer(value.get(key), min, max);
    }
}
