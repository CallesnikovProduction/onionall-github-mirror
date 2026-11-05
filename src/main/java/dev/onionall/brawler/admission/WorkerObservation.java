package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * <b>Наблюдение локального процесса и archive ownership.</b>
 *
 * <p>Не допускает URL и не изменяет процессы; неизвестную идентичность сохраняет как неопределённость.
 */
public final class WorkerObservation {

    public record Observation(JobStatus.Worker worker, Map<String, Object> details) {
    }

    /**
     * <b>Определяет локальную область идентичности процессов.</b>
     *
     * <p>Хэширует Windows host либо Linux boot/PID namespace; при ошибке возвращает null.
     *
     * @return хэш области или null
     */
    static String scope() {
        try {
            String identity;
            if (System.getProperty("os.name").startsWith("Windows")) {
                identity = System.getenv("COMPUTERNAME");
                if (identity == null || identity.isBlank()) {
                    return null;
                }
                identity = "windows:" + identity;
            } else {
                identity = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip() + ":" +
                    Files.readSymbolicLink(Path.of("/proc/self/ns/pid"));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * <b>Наблюдает время запуска PID.</b>
     *
     * <p>Недоступные сведения возвращает как null, не подтверждая идентичность.
     *
     * @param pid идентификатор процесса
     * @return start instant или null
     */
    static String start(long pid) {
        try {
            return ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Instant::toString).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * <b>Наблюдает процесс с ожидаемым start instant.</b>
     *
     * <p>Различает отсутствие процесса, несовпадение идентичности и недоступность сведений.
     *
     * @param pid идентификатор процесса
     * @param expected ожидаемый start instant
     * @return true, false либо null при неопределённости
     */
    static Boolean alive(Object pid, Object expected) {
        if (!(pid instanceof Integer || pid instanceof Long) || !(pid instanceof Number n) || n.longValue() < 1 ||
            !(expected instanceof String clock)) {
            return null;
        }
        try {
            Instant instant = Instant.parse(clock);
            Optional<ProcessHandle> process = ProcessHandle.of(n.longValue());
            if (process.isEmpty() || !process.get().isAlive()) {
                return false;
            }
            Optional<Instant> actual = process.get().info().startInstant();
            if (actual.isEmpty()) {
                return null;
            }
            return actual.get().equals(instant) && process.get().isAlive();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * <b>Наблюдает состояние существующего archive-lock.</b>
     *
     * <p>Не создаёт файл; ошибка доступа остаётся неопределённостью.
     *
     * @param path проверяемый путь
     * @return состояние lock либо null
     */
    private static Boolean lockHeld(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
                return lock == null;
            }
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * <b>Создаёт недоступное наблюдение worker.</b>
     *
     * <p>Не выдаёт отсутствие данных за подтверждённую остановку.
     *
     * @param basis основание наблюдения
     * @return наблюдение с указанным основанием
     */
    public static Observation unavailable(String basis) {
        return observation(null, null, null, null, null, basis);
    }

    /**
     * <b>Формирует неизменяемые детали наблюдения.</b>
     *
     * <p>Добавляет время наблюдения без изменения процессов или архива.
     *
     * @param alive наблюдение живого процесса
     * @param owner наблюдение владельца
     * @param child наблюдение дочернего процесса
     * @param held состояние lock
     * @param exit код завершения
     * @param basis основание наблюдения
     * @return наблюдение worker
     */
    private static Observation observation(
        Boolean alive,
        Boolean owner,
        Boolean child,
        Boolean held,
        Integer exit,
        String basis
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("alive", alive);
        details.put("owner_alive", owner);
        details.put("child_alive", child);
        details.put("ownership_lock_held", held);
        details.put("exit_code", exit);
        details.put("basis", basis);
        details.put("observed_at", Instant.now().toString());
        return new Observation(new JobStatus.Worker(alive, exit, basis), Collections.unmodifiableMap(details));
    }

    /**
     * <b>Читает execution и ownership-наблюдения.</b>
     *
     * <p>Проверяет локальную область и идентичности; ошибка чтения возвращает недоступное наблюдение. Файлы не изменяет.
     *
     * @param execution путь execution JSON
     * @param archive каталог архива
     * @param plan утверждённый план
     * @return проверенное или недоступное наблюдение
     */
    public static Observation read(Path execution, Path archive, String plan) {
        try {
            Path lockPath = archive.resolve("crawl-worker.lock");
            if (Files.exists(WorkerOwnership.marker(lockPath), LinkOption.NOFOLLOW_LINKS)) {
                Map<String, Object> marker = WorkerOwnership.read(lockPath);
                if (marker.get("plan_id") instanceof String ownedPlan && !plan.equals(ownedPlan)) {
                    return unavailable("ARCHIVE_OWNED_BY_OTHER_PLAN");
                }
                Boolean owner = alive(marker.get("owner_pid"), marker.get("owner_start"));
                Boolean child = Boolean.FALSE.equals(marker.get("child_created")) ? Boolean.FALSE :
                    alive(marker.get("child_pid"), marker.get("child_start"));
                Boolean held = lockHeld(lockPath), running = null;
                if (Boolean.TRUE.equals(child) || Boolean.TRUE.equals(held)) {
                    running = Boolean.TRUE;
                } else if (Boolean.TRUE.equals(owner) && !"STOPPED".equals(marker.get("phase"))) {
                    running = Boolean.TRUE;
                } else if (Boolean.FALSE.equals(held)) {
                    try {
                        WorkerOwnership.stopped(marker);
                        running = Boolean.FALSE;
                    } catch (java.io.IOException unknown) {
                        /* retained intent is not stopped proof */
                    }
                }
                Integer exit = marker.get("exit_code") instanceof Integer code ? code : null;
                return observation(running, owner, child, held, exit, "ARCHIVE_OWNERSHIP_V1");
            }
            if (!Files.isRegularFile(execution, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable("NO_EXECUTION_OBSERVATION");
            }
            byte[] bytes;
            try (var input = Files.newInputStream(execution)) {
                bytes = input.readNBytes(16385);
            }
            if (bytes.length > 16384) {
                return unavailable("INVALID_EXECUTION_OBSERVATION");
            }
            Map<String, Object> value = new ObjectMapper().readValue(bytes, new TypeReference<Map<String, Object>>() {
            });
            if (!plan.equals(value.get("plan_id"))) {
                return unavailable("EXECUTION_PLAN_MISMATCH");
            }
            String current = scope();
            if (current == null || !current.equals(value.get("process_scope"))) {
                return unavailable("PROCESS_SCOPE_UNVERIFIED");
            }
            Boolean owner = alive(value.get("worker_owner_pid"), value.get("worker_owner_start_instant"));
            Boolean child = alive(value.get("worker_pid"), value.get("worker_start_instant"));
            Boolean held = lockHeld(archive.resolve("crawl-worker.lock"));
            Boolean running = null;
            if (Boolean.TRUE.equals(owner) || Boolean.TRUE.equals(child)) {
                running = Boolean.TRUE;
            }
            // Legacy roots disappearing cannot prove cleanup of descendants. Remain UNKNOWN.
            Object code = value.get("worker_exit_code");
            Integer exit = code instanceof Integer i ? i : null;
            return observation(running, owner, child, held, exit, "PROCESS_HANDLES_START_INSTANT_AND_SCOPE");
        } catch (Exception e) {
            return unavailable("EXECUTION_OBSERVATION_UNAVAILABLE");
        }
    }
}
