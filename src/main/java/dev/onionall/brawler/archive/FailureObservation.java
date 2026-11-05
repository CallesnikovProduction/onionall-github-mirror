package dev.onionall.brawler.archive;

/**
 * <b>Структурированное наблюдение ошибки.</b>
 *
 * <p>Не доказывает отдельную сетевую попытку или применение записи Frontier; nullable поля сохраняют неопределённость.
 */
public record FailureObservation(Phase phase, Source source, Integer httpStatus, Boolean retryable,
    Boolean terminal, Long nextDueEpoch, Transport transport) {

    public enum Phase {

        FETCH, ROBOTS, PARSE, ARCHIVE, UNKNOWN
    }

    public enum Source {

        STORM_STATUS_INTENT, CATALOG_FAILURE_CALL
    }

    public enum Transport {

        HTTP_ERROR,
        HTTP_OK,
        REDIRECT,
        REQUEST_TIMEOUT,
        SOCKS_FAILURE,
        PROTOCOL_CLOSED,
        SCOPE_REJECTED,
        ONION_UNREACHABLE,
        UNKNOWN
    }

    public FailureObservation {
        if (phase == null || source == null || transport == null || httpStatus != null && (httpStatus < 100 ||
            httpStatus > 599) || nextDueEpoch != null && nextDueEpoch < 0 || Boolean.TRUE.equals(terminal) &&
            Boolean.TRUE.equals(retryable)) {
            throw new IllegalArgumentException("Invalid structured failure observation");
        }
    }

    /**
     * <b>Создаёт наблюдение без подтверждённых деталей.</b>
     *
     * <p>Использует источник CATALOG_FAILURE_CALL и nullable признаки.
     *
     * @return неопределённое наблюдение
     */
    public static FailureObservation unknown() {
        return new FailureObservation(Phase.UNKNOWN, Source.CATALOG_FAILURE_CALL, null, null, null, null,
            Transport.UNKNOWN);
    }
}
