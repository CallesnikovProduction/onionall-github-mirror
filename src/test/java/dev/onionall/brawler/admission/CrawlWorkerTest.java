package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CrawlWorkerTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static List<String> java(String main,String...args) {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),main));
        command.addAll(List.of(args));return command;
    }
    public static final class Fixture { public static void main(String[] args) throws Exception {Thread.sleep(Long.parseLong(args[0]));} }
    public static final class Initiator {
        public static void main(String[] args)throws Exception {
            var command=java(CrawlWorker.class.getName(),args[0],args[1],args[2]);
            command.addAll(java(Fixture.class.getName(),"30000"));
            new ProcessBuilder(command).inheritIO().start();
            Thread.sleep(30000);
        }
    }
    private static void await(Path ready)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!Files.exists(ready) && System.nanoTime()<until) Thread.sleep(25);
        assertTrue("Worker readiness not published",Files.exists(ready));
    }
    @Test public void childOwnsLockAndDeadlineAfterInitiatorIsKilled()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),ready=root.resolve("ready.json");
        Process parent=new ProcessBuilder(java(Initiator.class.getName(),lock.toString(),ready.toString(),"4")).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
        ProcessHandle owner=null,actual=null;
        try {
            await(ready);
            var value=new ObjectMapper().readTree(Files.readAllBytes(ready));
            owner=ProcessHandle.of(value.path("owner_pid").asLong()).orElseThrow();
            actual=ProcessHandle.of(value.path("worker_pid").asLong()).orElseThrow();
            parent.destroyForcibly();assertTrue(parent.waitFor(5,TimeUnit.SECONDS));
            assertTrue("Worker owner must outlive initiating CLI SIGKILL",owner.isAlive());
            assertThrows(IOException.class,()->CrawlWorker.requireAvailable(lock));
            actual.onExit().get(12,TimeUnit.SECONDS);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean released=false;
            while(System.nanoTime()<until) {
                try { CrawlWorker.requireAvailable(lock);released=true;break; }catch(IOException held){Thread.sleep(25);}
            }
            assertTrue("Child-owned execution lock must release after bounded stop",released);
        } finally {
            parent.destroyForcibly();if(owner!=null&&owner.isAlive())owner.destroyForcibly();if(actual!=null&&actual.isAlive())actual.destroyForcibly();
        }
    }
    @Test public void secondOwnerCannotLaunchWhileFirstOwnsLock()throws Exception {
        Path root=temporary.newFolder().toPath(),lock=root.resolve("worker.lock"),ready=root.resolve("ready.json");
        var firstCommand=java(CrawlWorker.class.getName(),lock.toString(),ready.toString(),"3");firstCommand.addAll(java(Fixture.class.getName(),"30000"));
        Process first=new ProcessBuilder(firstCommand).redirectErrorStream(true).redirectOutput(root.resolve("first.log").toFile()).start();
        try {
            await(ready);Path secondReady=root.resolve("second-ready.json");
            var secondCommand=java(CrawlWorker.class.getName(),lock.toString(),secondReady.toString(),"1");secondCommand.addAll(java(Fixture.class.getName(),"30000"));
            Process second=new ProcessBuilder(secondCommand).redirectErrorStream(true).redirectOutput(root.resolve("second.log").toFile()).start();
            assertTrue(second.waitFor(5,TimeUnit.SECONDS));assertNotEquals(0,second.exitValue());assertFalse(Files.exists(secondReady));
            assertTrue(first.waitFor(12,TimeUnit.SECONDS));assertEquals(124,first.exitValue());CrawlWorker.requireAvailable(lock);
        } finally {first.destroy();first.waitFor(10,TimeUnit.SECONDS);}
    }
}
