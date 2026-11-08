package dev.onionall.brawler.admission;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * <b>Строгая проверка версионированных наблюдений Frontier.</b>
 *
 * <p>Неизвестные исходы не интерпретируются как успешный пустой результат.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class FrontierReadback {

    // Admission/state v1 bounds: a session quota is independent of its requested page size.
    static final int MAX_RESERVATIONS = 100;
    static final int MAX_PAGE_SIZE = 200;

    private FrontierReadback() {
    }

    /** A structurally valid authoritative specification conflicts with the approved plan. */
    static final class SessionConflict extends IOException {
        private SessionConflict() {
            super("Authoritative session differs from approved plan");
        }
    }

    public enum State {
        PENDING, HELD, SCHEDULED_WAIT, UNSCHEDULED, UNKNOWN
    }

    record Reservation(String outcome, String reservationId, String url, String sourceUrl, int depth) { }

    /** Validates immutable reservation identity/provenance; enqueued is a historical acknowledgement. */
    static Reservation reservation(Map<String, Object> reply, String session, String url) throws IOException {
        try {
            object(reply);
            String outcome = text(reply, "outcome", 32);
            String reservation = text(reply, "reservation_id", 36);
            String source = text(reply, "source_url", 2048);
            int depth = number(reply, "depth", 0, 5);
            if ((!"RESERVED".equals(outcome) && !"ALREADY_RESERVED".equals(outcome)) ||
                session == null || !session.equals(text(reply, "session_id", 64)) ||
                url == null || url.isBlank() || !url.equals(text(reply, "url", 2048)) ||
                !UUID.fromString(reservation).toString().equals(reservation) ||
                !Boolean.TRUE.equals(reply.get("enqueued")) ||
                (depth == 0 ? !source.isEmpty() : source.isBlank() || source.equals(url))) {
                throw new IllegalArgumentException();
            }
            return new Reservation(outcome, reservation, url, source, depth);
        } catch (RuntimeException invalid) {
            throw new IOException("Malformed Frontier reservation readback");
        }
    }

    public record Row(
        String reservationId,
        int    ordinal,
        String url,
        String sourceUrl,
        int    depth,
        State  state,
        Long   nextDueEpoch,
        Long   leaseUntilEpoch
    ) {
    }

    public record Page(
        String    sessionId,
        Instant   readbackAt,
        int       throughOrdinal,
        List<Row> rows,
        String    nextCursor
    ) {
    }

    public record Counts(
        String  sessionId,
        Instant readbackAt,
        int     reserved,
        int     pending,
        int     held,
        int     scheduledWait,
        int     unscheduled,
        int     unknown,
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
    public static void verifySession(
            Map<String, Object> session,
            Map<String, Object> plan
    ) throws IOException {

        try {
            object(session);
            object(plan);
            Map<String, Object> spec = object(session.get("spec"));
            String id = text(plan, "plan_id", 64);
            if (!id.matches("[A-Za-z0-9-]{1,64}")) {
                throw new IllegalArgumentException();
            }
            int pages = number(plan, "max_pages", 1, MAX_RESERVATIONS);
            int depth = number(plan, "depth", 0, 5);
            String origin = text(plan, "origin", 256);
            Set<String> expectedSeeds = seeds(plan.get("seeds"), false);
            Set<String> actualSeeds = seeds(spec.get("seeds"), true);
            String actualId = text(spec, "session_id", 64);
            String actualOrigin = text(spec, "origin", 256);
            int actualPages = number(spec, "max_pages", 1, MAX_RESERVATIONS);
            int actualDepth = number(spec, "max_depth", 0, 5);
            number(session, "reserved_count", 0, actualPages);
            // open canonicalizes local seed order/duplicates to a unique set;
            // authoritative stored specs must already contain unique seeds.
            if (!"SESSION_OPEN".equals(session.get("outcome")) ||
                !Boolean.TRUE.equals(plan.get("approved")) ||
                !Boolean.TRUE.equals(spec.get("approved")) ||
                !(session.get("sealed") instanceof Boolean) ||
                number(spec, "policy_version", 1, 1) != 1 ||
                !actualId.matches("[A-Za-z0-9-]{1,64}") ||
                origin.isBlank() || actualOrigin.isBlank() ||
                expectedSeeds.size() > pages || actualSeeds.size() > actualPages) {
                throw new IllegalArgumentException();
            }
            if (!id.equals(actualId) || !origin.equals(actualOrigin) || pages != actualPages ||
                depth != actualDepth || !actualSeeds.equals(expectedSeeds)) {
                throw new SessionConflict();
            }
        } catch (RuntimeException e) {
            throw new IOException("Malformed approved plan or authoritative session", e);
        }
    }

    private static Set<String> seeds(Object value, boolean unique) {
        if (!(value instanceof List<?> items) || items.isEmpty() || items.size() > MAX_RESERVATIONS) {
            throw new IllegalArgumentException();
        }
        Set<String> seeds = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof String seed) || seed.isBlank() || seed.length() > 2048 ||
                seed.indexOf(0) >= 0 || (!seeds.add(seed) && unique)) {
                throw new IllegalArgumentException();
            }
        }
        return seeds;
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
    static Page page(
            Map<String, Object> reply,
            String session,
            String offeredCursor,
            int limit
    ) throws IOException {
        try {
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException();
            }
            Instant at = envelope(reply, session, "STATE_LIST");
            int through = number(reply, "through_ordinal", 0, MAX_RESERVATIONS);
            if (!(reply.get("rows") instanceof List<?> items) || items.size() > limit) {
                throw new IllegalArgumentException();
            }
            List<Row> rows = new ArrayList<>();
            Set<String> reservations = new HashSet<>();
            Set<String> urls = new HashSet<>();
            int previous = 0;

            // Cursor freezes reservation membership, not scheduler state or time.
            // Each page is a separate authoritative observation under server locks.
            if (offeredCursor != null) {
                if (offeredCursor.isEmpty() || offeredCursor.length() > 256) {
                    throw new IllegalArgumentException();
                }
                String[] cursor = new String(
                        Base64.getUrlDecoder().decode(offeredCursor),
                        java.nio.charset.StandardCharsets.UTF_8
                ).split(":", -1);

                if (
                        cursor.length != 4 ||
                        !cursor[0].equals("1") ||
                        !cursor[1].equals(session) ||
                        !cursor[2].equals(Integer.toString(through)) ||
                        !cursor[3].matches("[0-9]{1,3}")
                ) {
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
                if (!UUID.fromString(reservation).toString().equals(reservation) || !reservations.add(reservation)) {
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
                Long due = value == null
                        ? null
                        : integer(value, 0, Long.MAX_VALUE);
                if ((state == State.UNKNOWN || state == State.UNSCHEDULED) != (due == null)) {
                    throw new IllegalArgumentException();
                }
                if (!scheduler.containsKey("lease_until_epoch")) {
                    throw new IllegalArgumentException();
                }
                Long lease = scheduler.get("lease_until_epoch") == null
                        ? null
                        : integer(scheduler.get("lease_until_epoch"), 0, Long.MAX_VALUE);
                if ((state == State.HELD) == (lease == null)) {
                    throw new IllegalArgumentException();
                }
                String url = text(row, "url", 2048);
                if (url.isBlank() || !urls.add(url)) {
                    throw new IllegalArgumentException();
                }
                rows.add(new Row(
                        reservation,
                        ordinal,
                        url,
                        text(row, "source_url", 2048),
                        number(row, "depth", 0, 6),
                        state,
                        due,
                        lease
                ));
            }
            if (!reply.containsKey("next_cursor")) {
                throw new IllegalArgumentException();
            }

            String cursor = reply.get("next_cursor") == null
                    ? null
                    : text(reply, "next_cursor", 256);
            if (
                    cursor != null && (cursor.isEmpty() ||
                    rows.isEmpty() ||
                    previous >= through)
            ) {
                throw new IllegalArgumentException();
            }
            if (cursor != null &&
                    !cursor.equals(
                            Base64
                                    .getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            ("1:" + session + ":" + through + ":" + previous)
                                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    )
                    )
            ) {
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

            int reserved = number(c, "reserved", 0, MAX_RESERVATIONS),
                    pending = number(c, "pending", 0, MAX_RESERVATIONS);

            int held = number(c, "held", 0, MAX_RESERVATIONS),
                    wait = number(c, "scheduled_wait", 0, MAX_RESERVATIONS);

            int unscheduled = number(c, "unscheduled", 0, MAX_RESERVATIONS),
                    unknown = number(c, "unknown", 0, MAX_RESERVATIONS);

            if (reserved != pending + held + wait + unscheduled + unknown) {
                throw new IllegalArgumentException();
            }

            // TODO(READBACK-003): Сохранять terminal/success/retry provenance в Frontier
            // до версии протокола с terminal counters: AdmissionState.count видит только
            // scheduler rows, UNSCHEDULED не доказывает исход. Нужны durable update/recovery,
            // совместимость старых данных и версия readback; JobStatusService не вправе
            // выводить COMPLETE из нулевых active counts. В v1 эти четыре поля строго null.
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
        if (
                session == null || !session.matches("[A-Za-z0-9-]{1,64}") ||
                !outcome.equals(reply.get("outcome")) ||
                !session.equals(reply.get("session_id")) ||
                number(reply, "state_version", 1, 1) != 1
        ) {
            throw new IllegalArgumentException();
        }

        // Server wall-clock observation time, not a sequence number or freshness lease.
        // Separate calls/pages may regress under clock adjustments; receiving a verified
        // response makes it current for that call. No comparison with the client's clock.
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
        if (
                !(value instanceof Map<?, ?> m) ||
                m.keySet().stream().anyMatch(k -> !(k instanceof String))
        ) {
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
    private static String text(
            Map<String, Object> value,
            String key,
            int max
    ) {
        if (
                !(value.get(key) instanceof String text) ||
                text.length() > max ||
                text.indexOf(0) >= 0
        ) {
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
