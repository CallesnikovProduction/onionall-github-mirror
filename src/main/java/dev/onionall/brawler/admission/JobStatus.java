package dev.onionall.brawler.admission;

/**
 * <b>Оценка состояния задания по независимым наблюдениям.</b>
 *
 * <p>Exit zero и число архивных страниц сами по себе не доказывают завершение обхода.
 */
public final class JobStatus {

    private JobStatus() {
    }

    public enum State {

        DRAFT,
        APPROVED,
        ADMITTING,
        ADMITTED,
        RUNNING,
        PAUSED,
        COMPLETE,
        PARTIAL,
        FAILED,
        ABANDONED,
        UNKNOWN
    }

    public enum Completion {

        COMPLETE, INCOMPLETE, UNKNOWN
    }

    public record Worker(Boolean alive, Integer exitCode, String basis) {
    }

    public record Assessment(State state, Completion completion, String basis) {
    }

    /**
     * <b>Оценивает состояние по Frontier и worker.</b>
     *
     * <p>Завершение требует доказанных исходов; кэш и exit zero не заменяют это доказательство.
     *
     * @param approved подтверждён ли план
     * @param admitted есть ли наблюдение admission
     * @param readbackRequested запрошено ли актуальное наблюдение Frontier
     * @param counts авторитетные счётчики
     * @param worker наблюдение worker
     * @return состояние и основание оценки
     */
    public static Assessment assess(
        boolean approved,
        boolean admitted,
        boolean readbackRequested,
        FrontierReadback.Counts counts,
        Worker worker
    ) {
        if (counts == null) {
            if (!admitted && !readbackRequested) {
                return new Assessment(approved ? State.APPROVED : State.DRAFT, Completion.UNKNOWN,
                    "CATALOG_APPROVAL_ONLY");
            }
            return new Assessment(State.UNKNOWN, Completion.UNKNOWN, "AUTHORITATIVE_READBACK_UNAVAILABLE");
        }
        int scheduled = counts.pending() + counts.held() + counts.scheduledWait();
        Completion completeness = scheduled > 0 ? Completion.INCOMPLETE : Completion.UNKNOWN;
        if (Boolean.TRUE.equals(worker.alive())) {
            return new Assessment(State.RUNNING, completeness, "SERVER_READBACK_AND_PROCESS");
        }
        if (counts.unknown() > 0) {
            return new Assessment(State.UNKNOWN, completeness, "SERVER_ROWS_UNRESOLVED");
        }
        if (counts.reserved() == 0) {
            return new Assessment(State.ADMITTED, Completion.UNKNOWN, "SERVER_READBACK");
        }
        if (scheduled > 0) {
            return new Assessment(Boolean.FALSE.equals(worker.alive()) ? State.PAUSED : State.UNKNOWN,
                Completion.INCOMPLETE, Boolean.FALSE.equals(worker.alive()) ?
                "SERVER_READBACK_AND_STOPPED_PROCESS" : "PROCESS_LIVENESS_UNKNOWN");
        }
        if (!Boolean.FALSE.equals(worker.alive())) {
            return new Assessment(State.UNKNOWN, Completion.UNKNOWN, "PROCESS_LIVENESS_UNKNOWN");
        }
        // Current v1 client only returns nulls here; do not turn H2 pages or
        // historical failure observations into synthetic authoritative counts.
        if (counts.done() != null && counts.success() != null && counts.terminal() != null && counts.done() >= 0 &&
            counts.terminal() >= 0 && counts.success().equals(counts.done()) && counts.done() +
            counts.terminal() == counts.reserved() && counts.unscheduled() == counts.reserved()) {
            if (counts.terminal() > 0) {
                return new Assessment(State.PARTIAL, Completion.INCOMPLETE, "PROVEN_TERMINAL_OUTCOMES");
            }
            return new Assessment(State.COMPLETE, Completion.COMPLETE, "PROVEN_SUCCESS_OUTCOMES");
        }
        return new Assessment(State.UNKNOWN, Completion.UNKNOWN, "TERMINAL_PROVENANCE_UNKNOWN");
    }
}
