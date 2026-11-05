package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.onionall.brawler.admission.CrawlRuntimePreparer.write;

/**
 * <b>Запуск и ограниченное ожидание локального worker.</b>
 *
 * <p>Сохраняет handoff, readiness и проверку идентичности процессов; не выполняет admission.
 */
final class WorkerLauncher {

    private static final ObjectMapper EXECUTION_JSON = new ObjectMapper();

    private WorkerLauncher() {
    }

    /**
     * <b>Передаёт ownership wrapper и ожидает его завершения.</b>
     *
     * <p>Во время живого worker пишет только runtime JSON. При timeout сохраняет
     * прежнее наблюдение после попытки остановки; exit zero не доказывает завершение обхода.
     *
     * @param archive каталог архива для наблюдения timeout
     * @param id идентификатор задания
     * @param work рабочий каталог
     * @param storm исполняемый файл Storm
     * @param jobJar подготовленный worker JAR
     * @param runtimeWorkerLock archive-local lock worker
     * @param seconds ограничение времени обхода
     * @param preparation владелец handoff
     * @param session подтверждённая сессия Frontier
     * @return наблюдение завершившегося worker
     * @throws IOException при ошибке запуска, readiness или timeout
     */
    static Map<String, Object> run(
        Path archive,
        String id,
        Path work,
        Path storm,
        Path jobJar,
        Path runtimeWorkerLock,
        int seconds,
        WorkerOwnership.Preparation preparation,
        Map<String, Object> session
    ) throws IOException {
        Path topology = work.resolve("topology.json");
        Path log = work.resolve("worker-" + UUID.randomUUID() + ".log");
        Path ready = work.resolve("worker-ready-" + UUID.randomUUID() + ".json");
        ProcessBuilder launcher = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-cp", absoluteClasspath(), CrawlWorker.class.getName(), runtimeWorkerLock.toString(),
            ready.toString(), Integer.toString(seconds + 90), storm.toString(), "local", "-c", "storm.log.dir=" +
            work.resolve("logs"), "-c", "storm.local.dir=" + work.resolve("storm-local"), "-c",
            "dev.zookeeper.path=" + work.resolve("zookeeper"), jobJar.toString(), "org.apache.storm.flux.Flux",
            topology.toString(), "--local-ttl", Integer.toString(seconds)).directory(work.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        launcher.environment().put(WorkerOwnership.HANDOFF, preparation.handoff());
        Process worker = launcher.start();
        Instant workerIdentity = worker.info().startInstant().orElse(null);
        Thread stopping = new Thread(() -> stop(worker, workerIdentity), "onionall-cli-worker-stop");
        Runtime.getRuntime().addShutdownHook(stopping);
        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!Files.exists(ready) && worker.isAlive() && System.nanoTime() < readyDeadline) {
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            stop(worker, workerIdentity);
            Thread.currentThread().interrupt();
            throw new IOException("Worker startup interrupted", e);
        }
        if (!Files.exists(ready)) {
            stop(worker, workerIdentity);
            throw new IOException("Bounded worker could not acquire execution ownership; inspect " + log);
        }
        Map<String, Object> readiness = EXECUTION_JSON.readValue(Files.readAllBytes(ready),
            new TypeReference<Map<String, Object>>() {
        });
        if (!(readiness.get("owner_pid") instanceof Number ownerPid) || ownerPid.longValue() != worker.pid()) {
            stop(worker, workerIdentity);
            throw new IOException("Worker ownership readback mismatch");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("plan_id", id);
        result.put("admission", "COMMITTED");
        result.put("authoritative_admission", session);
        result.put("worker_pid", readiness.get("worker_pid"));
        result.put("worker_owner_pid", worker.pid());
        result.put("worker_log", log.toString());
        result.put("process_scope", WorkerObservation.scope());
        result.put("worker_owner_start_instant", workerIdentity == null ? null : workerIdentity.toString());
        result.put("worker_start_instant", readiness.get("worker_start_instant"));
        result.put("started_at", Instant.now().toString());
        result.put("state", "RUNNING");
        // Do not reopen embedded H2 while the live worker can own a callback connection.
        write(work.resolve("execution.json"), result);
        try {
            if (!worker.waitFor(seconds + 115L, TimeUnit.SECONDS)) {
                stop(worker, workerIdentity);
                result.put("state", "WORKER_TIMED_OUT");
                result.put("passed", false);
                write(work.resolve("execution.json"), result);
                try (var store = new ArchiveStore(archive)) {
                    store.recordAdmission(id, result);
                }
                throw new IOException("Worker exceeded bounded execution window; admitted work remains in Frontier");
            }
        } catch (InterruptedException e) {
            stop(worker, workerIdentity);
            Thread.currentThread().interrupt();
            throw new IOException("Crawl execution interrupted; resume the same plan", e);
        } finally {
            Runtime.getRuntime().removeShutdownHook(stopping);
        }
        result.put("worker_exit_code", worker.exitValue());
        result.put("state", worker.exitValue() == 0 ? "WORKER_EXITED" : "WORKER_FAILED");
        // Exit0 is not a crawl-completion assertion. Pending work/failures are read from owning systems.
        result.put("passed", worker.exitValue() == 0);
        result.put("crawl_completed_verified", false);
        return result;
    }

    /**
     * <b>Разрешает classpath до смены рабочего каталога дочернего процесса.</b>
     *
     * <p>Пустой элемент сохраняет значение текущего каталога родительской JVM.
     *
     * @return classpath с абсолютными путями
     */
    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path")
            .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(entry -> Path.of(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize().toString())
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    /**
     * <b>Проверяет живой процесс по start instant.</b>
     *
     * <p>Отсутствие времени запуска запрещает подтверждение идентичности.
     *
     * @param process проверяемый handle
     * @param start ожидаемое время запуска
     * @return подтверждена ли идентичность
     */
    private static boolean matches(ProcessHandle process, Instant start) {
        return start != null && process.isAlive() && process.info().startInstant().filter(start::equals).isPresent();
    }

    /**
     * <b>Посылает сигнал только после проверки того же handle.</b>
     *
     * <p>PID без совпадающего времени запуска не даёт права на остановку.
     *
     * @param process проверяемый процесс
     * @param start ожидаемое время запуска
     * @param force требуется ли принудительное завершение
     */
    private static void signal(ProcessHandle process, Instant start, boolean force) {
        if (matches(process, start)) {
            if (force) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    /**
     * <b>Останавливает только wrapper с подтверждённой идентичностью.</b>
     *
     * <p>Проверяет start instant перед каждым сигналом; STOPPED публикует только владелец.
     *
     * @param worker запущенный wrapper
     * @param identity start instant, полученный при запуске
     */
    static void stop(Process worker, Instant identity) {
        if (!worker.isAlive() || !matches(worker.toHandle(), identity)) {
            return;
        }
        var descendants = worker.descendants().map(p -> new AbstractMap.SimpleImmutableEntry<>(p, p.info()
            .startInstant().orElse(null))).filter(p -> p.getValue() != null).toList();
        if (!worker.isAlive() || !matches(worker.toHandle(), identity)) {
            return;
        }
        descendants.forEach(p -> signal(p.getKey(), p.getValue(), false));
        signal(worker.toHandle(), identity, false);
        try {
            if (!worker.waitFor(10, TimeUnit.SECONDS)) {
                descendants.forEach(p -> signal(p.getKey(), p.getValue(), true));
                signal(worker.toHandle(), identity, true);
                worker.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            descendants.forEach(p -> signal(p.getKey(), p.getValue(), true));
            signal(worker.toHandle(), identity, true);
            Thread.currentThread().interrupt();
        }
        // This caller never publishes STOPPED: only the owning wrapper can attest cleanup.
    }
}
