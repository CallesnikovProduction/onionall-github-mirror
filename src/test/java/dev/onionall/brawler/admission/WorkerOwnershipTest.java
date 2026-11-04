package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class WorkerOwnershipTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    public static final class PreparingOwner {
        public static void main(String[] args)throws Exception {
            try(var preparation=new WorkerOwnership.Preparation(Path.of(args[0]))) {
                if(args.length>2)preparation.handoff();
                Files.writeString(Path.of(args[1]),"ready");Thread.sleep(30000);
            }
        }
    }
    private Process launch(Path root,Path lock,Path ready,String millis)throws Exception {
        var command=CrawlWorkerTest.java(root,CrawlWorker.class.getName(),lock.toString(),ready.toString(),"2");
        command.addAll(CrawlWorkerTest.java(root,CrawlWorkerTest.Fixture.class.getName(),millis));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Files.createTempFile(root,"worker-",".log").toFile()).start();
    }
    private void await(Path ready)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!Files.exists(ready)&&System.nanoTime()<until)Thread.sleep(20);
        assertTrue(Files.exists(ready));
    }
    @Test public void normalExitPublishesStopProofAndRestartWorks()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock");
        for(int attempt=0;attempt<2;attempt++) {
            Process worker=launch(root,lock,root.resolve("ready-"+attempt),"1000");
            try {
                assertTrue(worker.waitFor(10,TimeUnit.SECONDS));assertEquals(0,worker.exitValue());
                CrawlWorker.requireAvailable(lock);
                var marker=WorkerOwnership.read(lock);assertEquals("STOPPED",marker.get("phase"));
                assertEquals(true,marker.get("child_created"));assertEquals(true,marker.get("cleanup_confirmed"));
            } finally {if(worker.isAlive()){worker.destroy();worker.waitFor(10,TimeUnit.SECONDS);}}
        }
    }
    @Test public void failedPreparationCanResumeWithoutAnyChild()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock");
        try(var preparation=new WorkerOwnership.Preparation(lock)) {
            assertEquals("PREPARING",WorkerOwnership.read(lock).get("phase"));
            assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
        }
        CrawlWorker.requireAvailable(lock);assertEquals(false,WorkerOwnership.read(lock).get("child_created"));
    }
    @Test public void forceKillBeforeChildPublicationBlocksRestart()throws Exception {
        for(String phase:List.of("PREPARING","LAUNCHING")) {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),ready=root.resolve("ready");
        var args=new ArrayList<>(List.of(lock.toString(),ready.toString()));if(phase.equals("LAUNCHING"))args.add("handoff");
        Process parent=new ProcessBuilder(CrawlWorkerTest.java(root,PreparingOwner.class.getName(),args.toArray(String[]::new)))
            .redirectErrorStream(true).redirectOutput(root.resolve("owner.log").toFile()).start();
        try {
            await(ready);parent.destroyForcibly();assertTrue(parent.waitFor(5,TimeUnit.SECONDS));
            byte[] before=Files.readAllBytes(WorkerOwnership.marker(lock));
            assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
            assertThrows(IOException.class,()->new WorkerOwnership.Preparation(lock));
            assertArrayEquals(before,Files.readAllBytes(WorkerOwnership.marker(lock)));
        } finally {if(parent.isAlive()){parent.destroyForcibly();parent.waitFor(5,TimeUnit.SECONDS);}}
        }
    }
    @Test public void releasedLegacyLockIsNotMigratedOrOverwritten()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock");
        Files.writeString(lock,"retained legacy metadata");byte[] before=Files.readAllBytes(lock);
        assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
        assertThrows(IOException.class,()->new WorkerOwnership.Preparation(lock));
        assertArrayEquals(before,Files.readAllBytes(lock));assertFalse(Files.exists(WorkerOwnership.marker(lock)));
    }
    @Test public void corruptForeignAndFutureVersionMarkersRemainUnknown()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),marker=WorkerOwnership.marker(lock);
        Files.writeString(lock,"");
        for(String invalid:List.of("{","null","[]")) {
            Files.writeString(marker,invalid);assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));assertEquals(invalid,Files.readString(marker));
        }
        for(String field:List.of("scope","ownership_version","lock_path","owner_start","owner_pid","phase","child_created","cleanup_confirmed")) {
            var value=WorkerOwnership.intent(lock,"STOPPED");value.put("cleanup_confirmed",true);value.put(field,"unverified");
            WorkerOwnership.publish(lock,value);byte[] before=Files.readAllBytes(marker);
            assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));assertArrayEquals(before,Files.readAllBytes(marker));
        }
    }
    @Test public void reusedPidIsNotKilledAndUncleanMarkerNeverBecomesStopped()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock");Files.writeString(lock,"");
        var value=WorkerOwnership.intent(lock,"RUNNING");value.put("child_created",true);
        value.put("child_pid",ProcessHandle.current().pid());value.put("child_start",Instant.EPOCH.toString());
        assertEquals(Boolean.FALSE,WorkerObservation.alive(value.get("child_pid"),value.get("child_start")));
        WorkerOwnership.publish(lock,value);assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
        // A pinned old identity can be absent only alongside an explicit cleanup witness.
        value.put("phase","STOPPED");value.put("cleanup_confirmed",true);WorkerOwnership.publish(lock,value);
        CrawlWorker.requireAvailable(lock);assertTrue(ProcessHandle.current().isAlive());
        value.put("child_start",WorkerObservation.start(ProcessHandle.current().pid()));WorkerOwnership.publish(lock,value);
        assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));assertTrue(ProcessHandle.current().isAlive());
    }
    @Test public void invalidHandoffCannotOverwriteRetainedIntent()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock");
        try(var preparation=new WorkerOwnership.Preparation(lock)) {
            String ticket=preparation.handoff();byte[] before=Files.readAllBytes(WorkerOwnership.marker(lock));
            assertThrows(IOException.class,()->WorkerOwnership.claim(lock,false,"wrong-ticket"));
            assertThrows(IOException.class,()->WorkerOwnership.claim(lock,false,ticket)); // self is not its own parent
            assertArrayEquals(before,Files.readAllBytes(WorkerOwnership.marker(lock)));
        }
        assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
    }
    @Test public void ownerDestroyUsesPlatformSemanticsWithoutAnonymousRecovery()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),ready=root.resolve("ready");
        Process owner=launch(root,lock,ready,"30000");ProcessHandle child=null;String start=null;
        try {
            await(ready);var marker=WorkerOwnership.read(lock);
            long pid=((Number)marker.get("child_pid")).longValue();start=(String)marker.get("child_start");
            child=ProcessHandle.of(pid).orElseThrow();
            owner.destroy();assertTrue(owner.waitFor(12,TimeUnit.SECONDS));
            if(System.getProperty("os.name").startsWith("Windows")) {
                // Windows Process.destroy does not supply a Java shutdown-hook contract.
                assertEquals(Boolean.TRUE,WorkerObservation.alive(pid,start));
                assertEquals("RUNNING",WorkerOwnership.read(lock).get("phase"));
                assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
            } else {
                assertEquals(Boolean.FALSE,WorkerObservation.alive(pid,start));
                CrawlWorker.requireAvailable(lock);
            }
        }finally {
            if(owner.isAlive()){owner.destroy();owner.waitFor(5,TimeUnit.SECONDS);}
            if(child!=null&&child.isAlive()) {
                assertEquals(start,child.info().startInstant().orElseThrow().toString());
                child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS);
            }
        }
    }
    @Test public void observedChildTerminationAllowsOwnerToPublishProof()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),ready=root.resolve("ready");
        Process owner=launch(root,lock,ready,"30000");ProcessHandle child=null;
        try {
            await(ready);var marker=WorkerOwnership.read(lock);long pid=((Number)marker.get("child_pid")).longValue();
            child=ProcessHandle.of(pid).orElseThrow();
            assertEquals(Boolean.TRUE,WorkerObservation.alive(pid,marker.get("child_start")));
            child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS);
            assertTrue(owner.waitFor(10,TimeUnit.SECONDS));assertNotEquals(0,owner.exitValue());
            CrawlWorker.requireAvailable(lock);
        } finally {if(owner.isAlive()){owner.destroy();owner.waitFor(10,TimeUnit.SECONDS);}}
    }
}
