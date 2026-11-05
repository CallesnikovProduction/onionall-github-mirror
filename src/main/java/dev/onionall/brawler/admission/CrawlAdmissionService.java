package dev.onionall.brawler.admission;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static dev.onionall.brawler.admission.AdmissionClient.fromRuntime;

/**
 * <b>Допуск начальных URL через авторитетную сессию Frontier.</b>
 *
 * <p>Архив не участвует в резервировании; анонимных повторов нет.
 */
final class CrawlAdmissionService {

    private CrawlAdmissionService() {
    }

    /**
     * <b>Открывает сессию и проверяет readback каждого seed.</b>
     *
     * <p>Вызывается после проверки явного approval; спецификация сверяется дважды.
     *
     * @param id идентификатор плана и сессии
     * @param plan утверждённый план
     * @param runtime параметры подключения
     * @param capability проверенный capability-токен
     * @return подтверждённая сессия
     * @throws IOException при отказе admission или несовпадении readback
     */
    static Map<String, Object> admit(
        String id,
        Map<String, Object> plan,
        Map<String, Object> runtime,
        String capability
    ) throws IOException {
        Map<String, Object> session;
        try (var rpc = fromRuntime(runtime, capability)) {
            var open = new LinkedHashMap<String, Object>();
            open.put("action", "open");
            open.put("session_id", id);
            open.put("origin", plan.get("origin"));
            open.put("seeds", plan.get("seeds"));
            open.put("max_pages", plan.get("max_pages"));
            open.put("max_depth", plan.get("depth"));
            open.put("approved", true);
            var opened = rpc.command(open);
            if (!"SESSION_OPEN".equals(opened.get("outcome"))) {
                throw new IOException("Authoritative session refused: " + opened.get("outcome"));
            }
            FrontierReadback.verifySession(opened, plan);
            for (Object seed : (List<?>) plan.get("seeds")) {
                var reservation = rpc.command(Map.of("action", "reserve", "session_id", id, "url", seed,
                    "source_url", "", "depth", 0));
                if (!Set.of("RESERVED", "ALREADY_RESERVED").contains(reservation.get("outcome"))) {
                    throw new IOException("Authoritative seed admission refused: " + reservation.get("outcome"));
                }
                var verified = rpc.command(Map.of("action", "reservation", "session_id", id, "url", seed));
                if (!Objects.equals(reservation.get("reservation_id"), verified.get("reservation_id")) ||
                    verified.get("reservation_id") == null) {
                    throw new IOException("Committed admission readback mismatch");
                }
            }
            session = rpc.command(Map.of("action", "session", "session_id", id));
            FrontierReadback.verifySession(session, plan);
        }
        return session;
    }
}
