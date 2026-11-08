package dev.onionall.brawler.admission;

import java.io.IOException;
import java.nio.file.Path;
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
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class CrawlAdmissionService {

    enum FailureOutcome { REJECTED, UNKNOWN, CONFLICT, PROTOCOL_VIOLATION }

    static final class AdmissionFailure extends IOException {
        private final FailureOutcome outcome;

        AdmissionFailure(FailureOutcome outcome, IOException cause) {
            super("Admission " + outcome + "; worker launch blocked", cause);
            this.outcome = outcome;
        }

        FailureOutcome outcome() { return outcome; }
    }

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
        validatePlanIdentity(id, plan);
        try (var rpc = fromRuntime(runtime, capability)) {
            return admit(id, plan, rpc);
        }
    }

    static Map<String, Object> admitWithCapabilityFile(String id, Map<String, Object> plan,
        Map<String, Object> runtime, Path capabilityFile) throws IOException {
        validatePlanIdentity(id, plan);
        try (var rpc = AdmissionClient.fromRuntimeWithCapabilityFile(runtime, capabilityFile)) {
            return admit(id, plan, rpc);
        }
    }

    private static void validatePlanIdentity(String id, Map<String, Object> plan) throws IOException {
        if (plan == null || !Boolean.TRUE.equals(plan.get("approved"))) {
            throw new IOException("Explicit plan approval is required before admission");
        }
        if (id == null || !id.equals(plan.get("plan_id"))) {
            throw new IOException("Admission plan identity mismatch");
        }
    }

    private static Map<String, Object> admit(String id, Map<String, Object> plan, AdmissionClient rpc)
        throws IOException {
        var open = new LinkedHashMap<String, Object>();
        open.put("action", "open");
        open.put("session_id", id);
        open.put("origin", plan.get("origin"));
        open.put("seeds", plan.get("seeds"));
        open.put("max_pages", plan.get("max_pages"));
        open.put("max_depth", plan.get("depth"));

        open.put("approved", true);

        var opened = mutate(rpc, open, Map.of("action", "session", "session_id", id), "SESSION_OPEN");
        if (opened == null || !"SESSION_OPEN".equals(opened.get("outcome"))) {
            throw refused(opened, Set.of("APPROVAL_REQUIRED", "SESSION_CONFLICT", "CRAWL_CONFLICT", "ADMISSION_READ_ONLY"));
        }
        verifySession(opened, plan);

        // Частичный commit сохраняется в Frontier; повтор той же сессии
        // возвращает прежние reservations и продолжает admission без rollback.
        for (Object seed : (List<?>) plan.get("seeds")) {
            var reservation = mutate(rpc,
                    Map.of(
                            "action", "reserve",
                            "session_id", id,
                            "url", seed,
                            "source_url", "",
                            "depth", 0
                    ),
                    Map.of("action", "reservation", "session_id", id, "url", seed), "RESERVED"
            );
            if (reservation == null || (!"RESERVED".equals(reservation.get("outcome")) &&
                !"ALREADY_RESERVED".equals(reservation.get("outcome")))) {
                throw refused(reservation, Set.of("SCOPE_REJECTED", "SEED_REJECTED", "SOURCE_REJECTED",
                    "DEPTH_REJECTED", "SESSION_SEALED", "LIMIT_REJECTED", "METADATA_ONLY", "ADMISSION_READ_ONLY",
                    "SESSION_NOT_FOUND"));
            }
            var verified = read(rpc,
                    Map.of(
                            "action", "reservation",
                            "session_id", id,
                            "url", seed
                    )
            );

            if (
                    verified == null ||
                    !"RESERVED".equals(verified.get("outcome")) ||
                    !id.equals(verified.get("session_id")) ||
                    !Objects.equals(seed, verified.get("url")) ||
                    !Integer.valueOf(0).equals(verified.get("depth")) ||
                    !"".equals(verified.get("source_url")) ||
                    !Boolean.TRUE.equals(verified.get("enqueued")) ||
                    !(verified.get("reservation_id") instanceof String reservationId) ||
                    reservationId.isBlank() ||
                    !Objects.equals(
                            reservation.get("reservation_id"),
                            verified.get("reservation_id")
                    )
            ) {
                throw new AdmissionFailure(FailureOutcome.PROTOCOL_VIOLATION, null);
            }
        }
        Map<String, Object> session = read(rpc,
                Map.of(
                        "action", "session",
                        "session_id", id
                )
        );
        if (session == null || !"SESSION_OPEN".equals(session.get("outcome"))) {
            throw new AdmissionFailure(FailureOutcome.PROTOCOL_VIOLATION, null);
        }
        verifySession(session, plan);
        return session;
    }

    private static void verifySession(Map<String, Object> session, Map<String, Object> plan) throws IOException {
        try {
            FrontierReadback.verifySession(session, plan);
        } catch (FrontierReadback.SessionConflict conflict) {
            throw new AdmissionFailure(FailureOutcome.CONFLICT, conflict);
        } catch (IOException mismatch) {
            throw new AdmissionFailure(FailureOutcome.PROTOCOL_VIOLATION, mismatch);
        }
    }

    private static AdmissionFailure refused(Map<String, Object> reply, Set<String> rejections) {
        Object outcome = reply == null ? null : reply.get("outcome");
        FailureOutcome failure = Set.of("SESSION_CONFLICT", "CRAWL_CONFLICT").contains(outcome == null ? "" : outcome)
            ? FailureOutcome.CONFLICT : rejections.contains(outcome == null ? "" : outcome)
            ? FailureOutcome.REJECTED : FailureOutcome.PROTOCOL_VIOLATION;
        return new AdmissionFailure(failure, null);
    }

    private static Map<String, Object> read(AdmissionClient rpc, Map<String, Object> command) throws IOException {
        try {
            return rpc.command(command);
        } catch (AdmissionClient.RpcFailure failure) {
            throw new AdmissionFailure(failure.kind() == AdmissionClient.FailureKind.TRANSPORT
                ? FailureOutcome.UNKNOWN : FailureOutcome.PROTOCOL_VIOLATION, failure);
        }
    }

    /** Resolve only by a positive authoritative read. A negative read never retries a mutation. */
    private static Map<String, Object> mutate(AdmissionClient rpc, Map<String, Object> command,
        Map<String, Object> readback, String committedOutcome) throws IOException {
        try {
            return rpc.command(command);
        } catch (AdmissionClient.RpcFailure original) {
            if (original.kind() != AdmissionClient.FailureKind.TRANSPORT) {
                throw new AdmissionFailure(FailureOutcome.PROTOCOL_VIOLATION, original);
            }
            Map<String, Object> observed;
            try {
                observed = rpc.command(readback);
            } catch (IOException unavailable) {
                var unresolved = new AdmissionFailure(FailureOutcome.UNKNOWN, original);
                unresolved.addSuppressed(unavailable);
                throw unresolved;
            }
            if (committedOutcome.equals(observed.get("outcome"))) {
                return observed;
            }
            if (Set.of("SESSION_NOT_FOUND", "RESERVATION_NOT_FOUND").contains(observed.get("outcome"))) {
                throw new AdmissionFailure(FailureOutcome.UNKNOWN, original);
            }
            throw new AdmissionFailure(FailureOutcome.PROTOCOL_VIOLATION, original);
        }
    }
}
