package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Local process observation only; never authorizes work or changes a process. */
final class WorkerObservation {
    record Observation(JobStatus.Worker worker, Map<String,Object> details) { }
    static String scope() {
        try {
            String identity;
            if (System.getProperty("os.name").startsWith("Windows")) {
                identity = System.getenv("COMPUTERNAME");
                if (identity == null || identity.isBlank()) return null;
                identity = "windows:" + identity;
            } else {
                identity = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip()
                    + ":" + Files.readSymbolicLink(Path.of("/proc/self/ns/pid"));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return null; }
    }
    static String start(long pid) {
        try { return ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Instant::toString).orElse(null); }
        catch (RuntimeException e) { return null; }
    }
    private static Boolean alive(Object pid, Object expected) {
        if (!(pid instanceof Integer || pid instanceof Long) || !(pid instanceof Number n) || n.longValue() < 1 || !(expected instanceof String clock)) return null;
        try {
            Instant instant = Instant.parse(clock);
            Optional<ProcessHandle> process = ProcessHandle.of(n.longValue());
            if (process.isEmpty()) return false;
            Optional<Instant> actual = process.get().info().startInstant();
            if (actual.isEmpty()) return null;
            return actual.get().equals(instant) && process.get().isAlive();
        } catch (RuntimeException e) { return null; }
    }
    private static Boolean lockHeld(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) { return lock == null; }
        } catch (OverlappingFileLockException e) { return true; }
        catch (Exception e) { return null; }
    }
    static Observation unavailable(String basis) { return observation(null, null, null, null, null, basis); }
    private static Observation observation(Boolean alive, Boolean owner, Boolean child, Boolean held, Integer exit, String basis) {
        Map<String,Object> details = new LinkedHashMap<>();
        details.put("alive", alive); details.put("owner_alive", owner); details.put("child_alive", child);
        details.put("ownership_lock_held", held); details.put("exit_code", exit); details.put("basis", basis);
        details.put("observed_at", Instant.now().toString());
        return new Observation(new JobStatus.Worker(alive, exit, basis), Collections.unmodifiableMap(details));
    }
    static Observation read(Path execution, Path archive, String plan) {
        try {
            if (!Files.isRegularFile(execution, LinkOption.NOFOLLOW_LINKS)) return unavailable("NO_EXECUTION_OBSERVATION");
            byte[] bytes;
            try (var input = Files.newInputStream(execution)) { bytes = input.readNBytes(16385); }
            if (bytes.length > 16384) return unavailable("INVALID_EXECUTION_OBSERVATION");
            Map<String,Object> value = new ObjectMapper().readValue(bytes, new TypeReference<Map<String,Object>>(){});
            if (!plan.equals(value.get("plan_id"))) return unavailable("EXECUTION_PLAN_MISMATCH");
            String current = scope();
            if (current == null || !current.equals(value.get("process_scope"))) return unavailable("PROCESS_SCOPE_UNVERIFIED");
            Boolean owner = alive(value.get("worker_owner_pid"), value.get("worker_owner_start_instant"));
            Boolean child = alive(value.get("worker_pid"), value.get("worker_start_instant"));
            Boolean held = lockHeld(archive.resolve("crawl-worker.lock"));
            Boolean running = null;
            if (Boolean.TRUE.equals(owner) || Boolean.TRUE.equals(child)) running = Boolean.TRUE;
            else if (Boolean.FALSE.equals(owner) && Boolean.FALSE.equals(child) && Boolean.FALSE.equals(held)) running = Boolean.FALSE;
            Object code = value.get("worker_exit_code");
            Integer exit = code instanceof Integer i ? i : null;
            return observation(running, owner, child, held, exit, "PROCESS_HANDLES_START_INSTANT_AND_SCOPE");
        } catch (Exception e) { return unavailable("EXECUTION_OBSERVATION_UNAVAILABLE"); }
    }
}
