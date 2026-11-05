package dev.onionall.brawler.admission;

import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.crawl.JobStatusService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static dev.onionall.brawler.admission.AdmissionClient.fromRuntime;
import static dev.onionall.brawler.admission.FrontierCapability.token;
import static dev.onionall.brawler.admission.CrawlRuntimePreparer.write;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.*;

/**
 * <b>Координатор локального запуска и наблюдения обхода.</b>
 *
 * <p>Удерживает startup-lock, проверяет approval и делегирует worker; Frontier остаётся владельцем расписания. Runtime задаёт оператор, не удалённые метаданные.
 */
public final class CrawlExecution {

    /**
     * <b>Получение и сохранение актуального состояния crawl-задания.</b>
     *
     * <p>Объединяет архивные данные, наблюдение локального worker
     * и проверенные сведения Frontier. Формирует итоговый статус,
     * сохраняет его в архиве и возвращает расширенную карту задания.
     *
     * @param archive путь к архиву заданий
     * @param id идентификатор crawl-задания
     * @param runtimeFile путь к runtime-конфигурации или {@code null}
     * @param tokenFile путь к admission capability-токену
     * @return карта задания с итоговым статусом и наблюдениями
     * @throws IOException при ошибках чтения, записи или конфигурации
     */
    public static Map<String, Object> status(
        Path archive,
        String id,
        Path runtimeFile,
        Path tokenFile
    ) throws IOException {
        return JobStatusService.reconcileStatus(archive, id, runtimeFile, tokenFile);
    }

    /**
     * <b>Запускает утверждённый план под локальными блокировками.</b>
     *
     * <p>Подготавливает файлы, подтверждает admission, ожидает worker и сохраняет наблюдения. Во время живого worker H2 не открывается штатным путём.
     *
     * @param archive каталог архива
     * @param id идентификатор задания
     * @param runtimeFile runtime-дескриптор оператора
     * @param tokenFile файл capability
     * @return наблюдение выполнения с версионированным статусом
     * @throws Exception при ошибке операции или нарушении её контракта
     */
    public static Map<String, Object> start(
        Path archive,
        String id,
        Path runtimeFile,
        Path tokenFile
    ) throws Exception {
        Map<String, Object> plan;
        try (var store = new ArchiveStore(archive)) {
            plan = store.plan(id);
        }
        if (!Boolean.TRUE.equals(plan.get("approved"))) {
            throw new IOException("Explicit plan approval is required before admission");
        }
        Map<String, Object> runtime = descriptor(runtimeFile);
        Path storm = Path.of(text(runtime, "storm_executable")).toAbsolutePath();
        Path jar = Path.of(text(runtime, "crawler_jar")).toAbsolutePath();
        Path work = Path.of(text(runtime, "work_directory")).toAbsolutePath().normalize().resolve(id);
        int seconds = number(runtime, "seconds", 120, 600);
        int httpTimeout = number(runtime, "http_timeout_ms", 20000, 30000);
        int retryMinutes = number(runtime, "retry_minutes", 120, 120);
        if (!Files.isRegularFile(storm) || !Files.isExecutable(storm) || !Files.isRegularFile(jar,
            LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime requires installed Storm executable and full crawler JAR");
        }
        String capability = token(tokenFile);
        try (FileChannel owner = FileChannel.open(archive.toAbsolutePath().normalize().resolve("crawl-start.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = owner.tryLock()) {
            if (lock == null) {
                throw new IOException("This archive already has an active local crawl start");
            }
            Path runtimeWorkerLock = archive.toAbsolutePath().normalize().resolve("crawl-worker.lock");
            if (!Files.exists(WorkerOwnership.marker(runtimeWorkerLock), LinkOption.NOFOLLOW_LINKS)) {
                try (var store = new ArchiveStore(archive)) {
                    if (store.hasExecutionHistory()) {
                        throw WorkerOwnership.blocked("legacy execution history lacks archive cleanup proof");
                    }
                }
            }
            try (var preparation = new WorkerOwnership.Preparation(runtimeWorkerLock, id)) {
                Path jobJar = CrawlRuntimePreparer.prepare(plan, runtime, archive, tokenFile, work, jar,
                    httpTimeout, retryMinutes);
                Map<String, Object> session = CrawlAdmissionService.admit(id, plan, runtime, capability);
                // This checkpoint can be reconstructed by the identical session readback after any crash.
                write(work.resolve("admission-observation.json"), session);
                try (var store = new ArchiveStore(archive)) {
                    store.recordAdmission(id, Map.of("admission", "COMMITTED", "state", "ADMITTED", "session_id",
                        id, "authoritative_admission", session, "runtime", runtimeFile.toAbsolutePath().toString(),
                        "observed_at", Instant.now().toString()));
                }
                Map<String, Object> result = WorkerLauncher.run(archive, id, work, storm, jobJar,
                    runtimeWorkerLock, seconds, preparation, session);
                try (var rpc = fromRuntime(runtime, capability)) {
                    result.put("authoritative_admission", rpc.command(Map.of("action", "session", "session_id", id)));
                } catch (IOException e) {
                    result.put("admission_readback_error", "AUTHORITATIVE_READBACK_UNAVAILABLE");
                }
                write(work.resolve("execution.json"), result);
                try (var store = new ArchiveStore(archive)) {
                    store.recordAdmission(id, result);
                }
                // Expose the same versioned job view as status; legacy passed/state
                // continue to describe execution only, never crawl completion.
                Map<String, Object> observed = status(archive, id, runtimeFile, tokenFile);
                if (observed.get("job_status") instanceof Map<?, ?> view) {
                    for (var entry : view.entrySet()) {
                        result.put((String) entry.getKey(), entry.getValue());
                    }
                    result.put("job_status", view);
                }
                write(work.resolve("execution.json"), result);
                return result;
            }
        }
    }

    /**
     * <b>Проверяет серверную сессию через FrontierReadback.</b>
     *
     * <p>Сохраняет совместимую точку входа; проверка не резервирует URL.
     *
     * @param session идентификатор или наблюдение сессии Frontier
     * @param plan утверждённый план
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    public static void verifySession(Map<String, Object> session, Map<String, Object> plan) throws IOException {
        FrontierReadback.verifySession(session, plan);
    }

    /**
     * Cleanup only a launch-pinned wrapper and its identified, directly observed descendants.
     */
    static void stop(Process worker, Instant identity) {
        WorkerLauncher.stop(worker, identity);
    }

    private CrawlExecution() {
        // No instances
    }
}
