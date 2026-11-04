package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WorkerObservationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static Map<String,Object> execution() {
        String start = WorkerObservation.start(ProcessHandle.current().pid());
        assertNotNull("Test requires process start identity",start);
        assertNotNull("Test requires supported local scope",WorkerObservation.scope());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("plan_id","p");result.put("process_scope",WorkerObservation.scope());
        result.put("worker_pid",ProcessHandle.current().pid());result.put("worker_owner_pid",ProcessHandle.current().pid());
        result.put("worker_start_instant",start);result.put("worker_owner_start_instant",start);return result;
    }
    private static void write(Path path, Map<String,Object> value) throws Exception {
        new ObjectMapper().writeValue(path.toFile(),value);
    }
    @Test public void exactProcessIdentityIsRequiredAndForeignScopeCannotBeRunning() throws Exception {
        Path root=temporary.newFolder().toPath(), file=root.resolve("execution.json");
        Map<String,Object> value=execution();write(file,value);
        assertEquals(Boolean.TRUE,WorkerObservation.read(file,root,"p").worker().alive());
        value.put("process_scope","foreign");write(file,value);
        assertNull(WorkerObservation.read(file,root,"p").worker().alive());
        value=execution();value.put("worker_start_instant",Instant.EPOCH.toString());value.put("worker_owner_start_instant",Instant.EPOCH.toString());write(file,value);
        assertEquals(Boolean.FALSE,WorkerObservation.read(file,root,"p").worker().alive());
        assertFalse(Files.exists(root.resolve("crawl-worker.lock")));
    }
    @Test public void lockWithoutIdentifiedProcessIsUnknownAndNeverMutatesLockBytes() throws Exception {
        Path root=temporary.newFolder().toPath(), file=root.resolve("execution.json"), lock=root.resolve("crawl-worker.lock");
        Map<String,Object> value=execution();value.put("worker_start_instant",Instant.EPOCH.toString());value.put("worker_owner_start_instant",Instant.EPOCH.toString());write(file,value);
        byte[] contents="retained-owner-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(lock,contents);
        try(var channel=FileChannel.open(lock,StandardOpenOption.READ,StandardOpenOption.WRITE);var held=channel.lock()) {
            var observed=WorkerObservation.read(file,root,"p");
            assertNull(observed.worker().alive());
            assertEquals(Boolean.TRUE,observed.details().get("ownership_lock_held"));
            assertEquals("PROCESS_HANDLES_START_INSTANT_AND_SCOPE",observed.worker().basis());
            // Windows mandatory locks reject a separate read handle, even in this JVM.
            var bytes=java.nio.ByteBuffer.allocate(contents.length);channel.read(bytes,0);
            assertArrayEquals(contents,bytes.array());
        }
        assertEquals(Boolean.FALSE,WorkerObservation.read(file,root,"p").worker().alive());
        assertArrayEquals(contents,Files.readAllBytes(lock));
    }
    @Test public void absentLegacyAndMalformedMarkersAreUnknownNotFailureOrEmptyState() throws Exception {
        Path root=temporary.newFolder().toPath(), file=root.resolve("execution.json");
        assertNull(WorkerObservation.read(file,root,"p").worker().alive());
        assertFalse(Files.exists(file));
        write(file,Map.of("plan_id","p","worker_pid",ProcessHandle.current().pid(),"worker_exit_code",0));
        assertNull(WorkerObservation.read(file,root,"p").worker().alive());
        Files.writeString(file,"not JSON");assertNull(WorkerObservation.read(file,root,"p").worker().alive());
        write(file,execution());assertNull(WorkerObservation.read(file,root,"other").worker().alive());
    }
}
