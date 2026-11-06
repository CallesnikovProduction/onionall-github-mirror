package dev.onionall.brawler.crawl;

import dev.onionall.brawler.admission.FrontierCapability;
import dev.onionall.brawler.admission.FrontierReadback;
import dev.onionall.brawler.admission.JobStatusView;
import dev.onionall.brawler.admission.WorkerObservation;
import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;
import static dev.onionall.brawler.admission.AdmissionClient.fromRuntime;
import static dev.onionall.brawler.admission.FrontierReadback.verifySession;

/**
 * <b>Получение и сохранение состояния crawl-заданий.</b>
 *
 * <p>Объединяет архивные данные, наблюдения локального worker
 * и подтверждённые сведения Frontier. Проверяет согласованность
 * admission-состояния, формирует итоговый статус и сохраняет
 * результаты наблюдений в архиве.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class JobStatusService {

    /**
     * <b>Получение актуального состояния crawl-задания.</b>
     *
     * <p>Загружает данные задания из архива, собирает наблюдения
     * worker и Frontier, формирует итоговый статус и сохраняет
     * результаты проверки вместе с историей переходов.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @param runtimeFile путь к runtime-конфигурации или {@code null}
     * @param tokenFile путь к файлу admission capability-токена
     * @return карта задания с актуальным статусом и наблюдениями
     * @throws IOException при ошибках доступа, конфигурации или проверки данных
     */
    public static Map<String, Object> reconcileStatus(
        Path archive,
        String id,
        Path runtimeFile,
        Path tokenFile
    ) throws IOException {
        Map<String, Object> result = loadJob(archive, id);

        String catalogAt = Instant.now().toString();

        WorkerObservation.Observation worker = WorkerObservation.unavailable("NO_RUNTIME_OBSERVATION");
        FrontierObservation frontier = new FrontierObservation(null, null, null);

        if (runtimeFile != null) {
            Map<String, Object> runtime = RuntimeDescriptor.descriptor(runtimeFile);
            worker = observeWorker(runtime, archive, id);
            frontier = readFrontier(runtime, tokenFile, result, id);

            if (frontier.counts() != null) {
                result.put("authoritative_admission", frontier.session());
                result.put("admission", "SERVER_READBACK");
                recordAdmission(archive, id, result, frontier.session());
            }
        }

        Map<String, Object> view = JobStatusView.assemble(
                result,
                frontier.counts(),
                worker,
                frontier.reachable(),
                runtimeFile != null,
                catalogAt
        );

        recordStatus(archive, id, result, view);
        result.put("job_status", view);
        result.putAll(view);
        return result;
    }

    /**
     * <b>Загрузка задания из архива.</b>
     *
     * <p>Получает сохранённые данные задания и возвращает
     * их изменяемую копию с сохранением порядка элементов.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @return изменяемая карта данных задания
     * @throws IOException при ошибке доступа к архиву
     */
    private static Map<String, Object> loadJob(
            Path archive,
            String id
    ) throws IOException {
        Map<String, Object> result;
        try (var store = new ArchiveStore(archive)) {
            result = new LinkedHashMap<>(store.job(id));
        }
        return result;
    }

    /**
     * <b>Получение локального наблюдения за worker.</b>
     *
     * <p>Определяет путь к файлу выполнения из runtime-конфигурации
     * и запрашивает состояние процесса через {@link WorkerObservation}.
     * При отсутствии корректного рабочего каталога возвращает
     * недоступное наблюдение.
     *
     * @param runtime карта параметров среды выполнения
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @return наблюдение о состоянии worker
     */
    private static WorkerObservation.Observation observeWorker(
        Map<String, Object> runtime,
        Path archive,
        String id
    ) {
        Object configured = runtime.get("work_directory");
        if (
                configured instanceof String directory && // pattern matching of String
                !directory.isBlank() &&
                directory.length() <= 4096
        ) {
            Path execution = Path
                    .of(directory)
                    .toAbsolutePath()
                    .normalize()
                    .resolve(id)
                    .resolve("execution.json");
            return WorkerObservation.read(execution, archive, id);
        }
        return WorkerObservation.unavailable("NO_RUNTIME_OBSERVATION");
    }

    /**
     * <b>Получение и проверка admission-состояния Frontier.</b>
     *
     * <p>Запрашивает серверную сессию и счётчики резервирования,
     * проверяет соответствие утверждённому плану и допустимым
     * ограничениям. При распознанной RPC-ошибке возвращает
     * наблюдение без подтверждённых счётчиков.
     *
     * @param runtime карта параметров подключения к Frontier
     * @param tokenFile путь к файлу admission capability-токена
     * @param job карта сохранённого задания
     * @param id идентификатор crawl-задания
     * @return результат наблюдения Frontier
     * @throws IOException при ошибках конфигурации, чтения токена
     *                     или нарушении согласованности данных
     */
    private static FrontierObservation readFrontier(
        Map<String, Object> runtime,
        Path tokenFile,
        Map<String, Object> job,
        String id
    ) throws IOException {
        String token = FrontierCapability.token(tokenFile);
        try (var rpc = fromRuntime(runtime, token)) {
            Map<String, Object> session;
            try {
                session = rpc.command(Map.of(
                        "action", "session",
                        "session_id", id)
                );
            } catch (IOException e) {
                return unavailableFrontier(e);
            }

            if (session == null)
                throw new IOException("Null authoritative session");
            if (!"SESSION_OPEN".equals(session.get("outcome")))
                throw new IOException("Authoritative session unavailable: " + session.get("outcome"));

            if (!Boolean.TRUE.equals(job.get("approved")))
                throw new IOException("Stored plan is not approved");

            verifySession(session, job);
            Object reserved = session.get("reserved_count");
            if (!(reserved instanceof Integer || reserved instanceof Long)) {
                throw new IOException("Invalid authoritative reservation count");
            }
            long sessionReserved = ((Number) reserved).longValue();
            long maxPages = maxPages(job);
            if (sessionReserved < 0 || sessionReserved > maxPages) {
                throw new IOException("Invalid authoritative reservation count");
            }
            FrontierReadback.Counts counts;
            try {
                counts = rpc.counts(id);
            } catch (IOException e) {
                return unavailableFrontier(e);
            }
            if (counts == null) {
                throw new IOException("Null Frontier readback");
            }
            if (counts.reserved() > maxPages || counts.reserved() < sessionReserved) {
                throw new IOException("Inconsistent Frontier readback");
            }

            return new FrontierObservation(session, counts, true);
        }
    }

    /**
     * <b>Определение доступности Frontier по RPC-ошибке.</b>
     *
     * <p>Анализирует цепочку причин исключения и преобразует
     * распознанную gRPC-ошибку в наблюдение о доступности сервера.
     * Если gRPC-причина отсутствует, повторно выбрасывает исключение.
     *
     * @param exception ошибка обращения к Frontier
     * @return наблюдение без подтверждённой сессии и счётчиков
     * @throws IOException если ошибка не связана с gRPC
     */
    private static FrontierObservation unavailableFrontier(IOException exception) throws IOException {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof io.grpc.StatusRuntimeException rpcFailure) {
                io.grpc.Status.Code code = rpcFailure.getStatus().getCode();
                Boolean reachable = switch(code) {
                    case UNAVAILABLE ->
                        Boolean.FALSE;
                    case DEADLINE_EXCEEDED ->
                        null;
                    default ->
                        Boolean.TRUE;
                };
                return new FrontierObservation(null, null, reachable);
            }
            cause = cause.getCause();
        }
        throw exception;
    }

    /**
     * <b>Получение ограничения количества страниц задания.</b>
     *
     * <p>Извлекает параметр {@code max_pages} из сохранённого
     * задания и проверяет его целочисленный тип и положительность.
     *
     * @param job карта сохранённого задания
     * @return максимально допустимое количество страниц
     * @throws IOException если ограничение отсутствует или некорректно
     */
    private static long maxPages(Map<String, Object> job) throws IOException {
        Object value = job.get("max_pages");
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IOException("Invalid stored max_pages");
        }
        long max = ((Number) value).longValue();
        if (max < 1) {
            throw new IOException("Invalid stored max_pages");
        }
        return max;
    }

    /**
     * <b>Сохранение подтверждённого admission-наблюдения.</b>
     *
     * <p>Обновляет архивные сведения о сессии Frontier,
     * фиксирует подтверждённое admission-состояние и время
     * наблюдения, сохраняя ранее установленное состояние задания.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @param job изменяемая карта данных задания
     * @param session подтверждённая серверная сессия Frontier
     * @throws IOException при ошибке чтения или записи архива
     */
    private static void recordAdmission(
        Path archive,
        String id,
        Map<String, Object> job,
        Map<String, Object> session
    ) throws IOException {
        try (var store = new ArchiveStore(archive)) {
            Map<String, Object> observation = new LinkedHashMap<>(store.admissionObservation(id));
            observation.putIfAbsent("state", "ADMITTED");
            observation.put("admission", "COMMITTED");
            observation.put("authoritative_admission", session);
            observation.put("session_id", id);
            observation.put("observed_at", Instant.now().toString());
            store.recordAdmission(id, observation);
            job.put("state", observation.get("state"));
        }
    }

    /**
     * <b>Сохранение итогового статуса crawl-задания.</b>
     *
     * <p>Записывает сформированное представление статуса в архив
     * и дополняет результирующую карту сведениями об истории
     * переходов состояния.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @param result изменяемая карта данных задания
     * @param view сформированное представление статуса
     * @throws IOException при ошибке чтения или записи архива
     */
    private static void recordStatus(
        Path archive,
        String id,
        Map<String, Object> result,
        Map<String, Object> view
    ) throws IOException {
        try (var store = new ArchiveStore(archive)) {
            store.recordJobStatus(id, view);
            Map<String, Object> recorded = store.admissionObservation(id);
            result.put("status_transitions", recorded.get("status_transitions"));
            result.put("status_transitions_dropped", recorded.get("status_transitions_dropped"));
        }
    }

    /**
     * <b>Результат наблюдения admission-состояния Frontier.</b>
     *
     * <p>Содержит подтверждённую серверную сессию, счётчики
     * резервирования и признак доступности Frontier.
     * При отсутствии достоверного readback значения
     * сессии и счётчиков могут быть {@code null}.
     *
     * @param session серверная сессия или {@code null}
     * @param counts счётчики Frontier или {@code null}
     * @param reachable признак доступности Frontier или {@code null}
     */
    private record FrontierObservation(
        Map<String, Object> session,
        FrontierReadback.Counts counts,
        Boolean reachable
    ) {
    }

    // No instances / Запрещены экземпляры
    private JobStatusService() {
    }
}
