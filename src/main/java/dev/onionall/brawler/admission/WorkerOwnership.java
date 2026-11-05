package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * <b>Локальный crash-журнал владения архивом.</b>
 *
 * <p>Не заменяет OS containment, scheduler или авторизацию. Неопределённый cleanup запрещает новый запуск.
 */
final class WorkerOwnership {

    static final String HANDOFF = "ONIONALL_WORKER_HANDOFF";

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /**
     * <b>Определяет путь версионированного ownership-журнала.</b>
     *
     * <p>Не создаёт файл и не меняет lock.
     *
     * @param lock путь archive-lock
     * @return путь соседнего JSON-файла
     */
    static Path marker(Path lock) {
        return lock.resolveSibling(lock.getFileName() + ".owner-v1.json");
    }

    /**
     * <b>Читает и проверяет ownership-журнал.</b>
     *
     * <p>Ограничивает размер, запрещает symlink, проверяет версию, scope и идентичности.
     *
     * @param lock путь archive-lock
     * @return проверенная карта ownership
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static Map<String, Object> read(Path lock) throws IOException {
        Path path = marker(lock);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw blocked("missing/legacy ownership proof");
        }
        byte[] bytes;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(16385);
        }
        if (bytes.length > 16384) {
            throw blocked("oversized ownership marker");
        }
        Map<String, Object> value;
        try {
            value = JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw blocked("invalid ownership marker");
        }
        if (value == null || !Integer.valueOf(1).equals(value.get("ownership_version")) ||
            WorkerObservation.scope() == null || !WorkerObservation.scope().equals(value.get("scope")) ||
            !lock.toAbsolutePath().normalize().toString().equals(value.get("lock_path")) ||
            !(value.get("run_id") instanceof String run) || !validRun(run)) {
            throw blocked("unknown version/scope/identity");
        }
        try {
            if (!(value.get("owner_pid") instanceof Integer || value.get("owner_pid") instanceof Long) ||
                ((Number) value.get("owner_pid")).longValue() < 1 ||
                !(value.get("owner_start") instanceof String clock) ||
                !(value.get("child_created") instanceof Boolean) ||
                !(value.get("cleanup_confirmed") instanceof Boolean) || !Set.of("PREPARING", "LAUNCHING",
                "RUNNING", "STOPPED", "UNKNOWN").contains(value.get("phase"))) {
                throw blocked("incomplete ownership record");
            }
            Instant.parse(clock);
        } catch (RuntimeException e) {
            throw blocked("invalid ownership identity");
        }
        return value;
    }

    private static boolean validRun(String run) {
        try {
            return UUID.fromString(run).toString().equals(run);
        } catch (Exception e) {
            return false;
        }
    }

    static IOException blocked(String reason) {
        return new IOException("Worker ownership BLOCKED: " + reason +
            "; inspect retained metadata, do not delete/force-resume");
    }

    /**
     * <b>Проверяет доказательство завершённого cleanup.</b>
     *
     * <p>Одного освобождённого lock недостаточно; живой или неизвестный child блокирует запуск.
     *
     * @param value проверяемые или публикуемые данные
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static void stopped(Map<String, Object> value) throws IOException {
        if (!"STOPPED".equals(value.get("phase")) || !Boolean.TRUE.equals(value.get("cleanup_confirmed"))) {
            throw blocked("previous child live or UNKNOWN");
        }
        if (!Boolean.FALSE.equals(value.get("child_created"))) {
            if (!Boolean.FALSE.equals(WorkerObservation.alive(value.get("child_pid"), value.get("child_start")))) {
                throw blocked("child stop identity not proved");
            }
        }
        if (!(value.get("owner_pid") instanceof Number) || !(value.get("owner_start") instanceof String)) {
            throw blocked("incomplete owner identity");
        }
    }

    /**
     * <b>Проверяет доступность архива для worker.</b>
     *
     * <p>Пробует существующий lock и проверяет retained cleanup; неопределённость запрещает запуск.
     *
     * @param lock путь archive-lock
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static void requireAvailable(Path lock) throws IOException {
        if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(marker(lock), LinkOption.NOFOLLOW_LINKS)) {
                stopped(read(lock));
            }
            return;
        }
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var held = channel.tryLock()) {
            if (held == null) {
                throw blocked("archive lock held");
            }
            stopped(read(lock));
        } catch (OverlappingFileLockException e) {
            throw blocked("archive lock held");
        }
    }

    /**
     * <b>Атомарно публикует ownership-журнал.</b>
     *
     * <p>Синхронизирует файл и пытается синхронизировать каталог. Неатомарного fallback нет; power-loss durability не гарантируется.
     *
     * @param lock путь archive-lock
     * @param value проверяемые или публикуемые данные
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static void publish(Path lock, Map<String, Object> value) throws IOException {
        Path path = marker(lock), partial = Files.createTempFile(path.getParent(), ".ownership-", ".tmp");
        try {
            try (var out = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(value));
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
                out.force(true);
            }
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Directory fsync is not portable (notably Windows). No non-atomic fallback.
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                /* power-loss durability is not claimed */
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * <b>Создаёт локальное намерение запуска.</b>
     *
     * <p>Требует известные scope/start instant; не публикует данные самостоятельно.
     *
     * @param lock путь archive-lock
     * @param phase фаза ownership
     * @return карта нового run intent
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static Map<String, Object> intent(Path lock, String phase) throws IOException {
        String scope = WorkerObservation.scope(), start = WorkerObservation.start(ProcessHandle.current().pid());
        if (scope == null || start == null) {
            throw blocked("local process identity unavailable");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ownership_version", 1);
        value.put("run_id", UUID.randomUUID().toString());
        value.put("scope", scope);
        value.put("lock_path", lock.toAbsolutePath().normalize().toString());
        value.put("phase", phase);
        value.put("owner_pid", ProcessHandle.current().pid());
        value.put("owner_start", start);
        value.put("child_created", false);
        value.put("cleanup_confirmed", false);
        value.put("observed_at", Instant.now().toString());
        return value;
    }

    /**
     * <b>Временный владелец подготовки worker.</b>
     *
     * <p>До handoff удерживает archive-lock; после передачи не публикует ложное подтверждение остановки.
     */
    static final class Preparation implements AutoCloseable {

        private final Path lock;

        private final FileChannel channel;

        private final FileLock held;

        private final Map<String, Object> value;

        private boolean handed, closed;

        Preparation(Path path) throws IOException {
            this(path, null);
        }

        /**
         * <b>Захватывает archive-lock и публикует PREPARING.</b>
         *
         * <p>Проверяет retained cleanup до нового намерения; при ошибке освобождает
         * уже захваченные локальные ресурсы. План может отсутствовать у probes.
         *
         * @param path путь archive-lock
         * @param plan идентификатор плана либо null
         * @throws IOException если владение не подтверждено или публикация не удалась
         */
        Preparation(Path path, String plan) throws IOException {
            lock = path;
            boolean fresh;
            FileChannel opened;
            try {
                opened = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                fresh = true;
            } catch (FileAlreadyExistsException e) {
                opened = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                fresh = false;
            }
            channel = opened;
            FileLock acquired = null;
            try {
                acquired = channel.tryLock();
                if (acquired == null) {
                    throw blocked("archive lock held");
                }
                if (!fresh || Files.exists(marker(lock), LinkOption.NOFOLLOW_LINKS)) {
                    stopped(read(lock));
                }
                value = intent(lock, "PREPARING");
                if (plan != null) {
                    value.put("plan_id", plan);
                }
                publish(lock, value);
                held = acquired;
            } catch (IOException | RuntimeException e) {
                if (acquired != null) {
                    acquired.release();
                }
                channel.close();
                throw e;
            }
        }

        /**
         * <b>Передаёт preparation ownership дочернему wrapper.</b>
         *
         * <p>Сначала публикует LAUNCHING, затем освобождает lock; повторная передача запрещена.
         *
         * @return идентификатор run для handoff
         * @throws IOException при ошибке операции или нарушении её контракта
         */
        String handoff() throws IOException {
            if (handed || closed) {
                throw blocked("preparation already completed");
            }
            value.put("phase", "LAUNCHING");
            publish(lock, value);
            handed = true;
            held.release();
            channel.close();
            return (String) value.get("run_id");
        }

        /**
         * <b>Завершает preparation ownership.</b>
         *
         * <p>До handoff публикует STOPPED и освобождает ресурсы; после handoff не утверждает cleanup child.
         *
         * @throws IOException при ошибке операции или нарушении её контракта
         */
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!handed) {
                    value.put("phase", "STOPPED");
                    value.put("cleanup_confirmed", true);
                    publish(lock, value);
                }
            } finally {
                if (held.isValid()) {
                    held.release();
                }
                if (channel.isOpen()) {
                    channel.close();
                }
            }
        }
    }

    /**
     * <b>Проверяет handoff и принимает ownership wrapper.</b>
     *
     * <p>Сверяет run, фазу и родительскую идентичность; публикует нового владельца без анонимного обхода guard.
     *
     * @param lock путь archive-lock
     * @param fresh создан ли новый lock
     * @param ticket handoff run id либо null
     * @return карта принятого ownership
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    static Map<String, Object> claim(Path lock, boolean fresh, String ticket) throws IOException {
        if (ticket != null) {
            var value = read(lock);
            var parent = ProcessHandle.current().parent();
            if (!ticket.equals(value.get("run_id")) || !"LAUNCHING".equals(value.get("phase")) ||
                !Boolean.TRUE.equals(WorkerObservation.alive(value.get("owner_pid"), value.get("owner_start"))) ||
                parent.isEmpty() || !(value.get("owner_pid") instanceof Number pid) || parent.get()
                .pid() != pid.longValue()) {
                throw blocked("invalid launch handoff");
            }
            value.put("owner_pid", ProcessHandle.current().pid());
            value.put("owner_start", WorkerObservation.start(ProcessHandle.current().pid()));
            if (value.get("owner_start") == null) {
                throw blocked("wrapper identity unavailable");
            }
            publish(lock, value);
            return value;
        }
        if (!fresh || Files.exists(marker(lock), LinkOption.NOFOLLOW_LINKS)) {
            stopped(read(lock));
        }
        var value = intent(lock, "LAUNCHING");
        publish(lock, value);
        return value;
    }
}
