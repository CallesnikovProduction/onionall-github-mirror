package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
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
    static List<String> java(Path root,String main,String...args) throws IOException {
        return javaWithClasspath(root,System.getProperty("java.class.path"),main,args);
    }
    private static List<String> javaWithClasspath(Path root,String classpath,String main,String...args) throws IOException {
        Path arguments=Files.createTempFile(root,"java-",".args");
        // Files belong to the outer test's directory, which outlives an initiating
        // JVM hard kill and is removed only after all child-process assertions.
        var values=new ArrayList<>(List.of("--disable-@files","-cp",classpath,main));
        values.addAll(List.of(args));
        StringBuilder text=new StringBuilder();
        for(String value:values) text.append('"').append(value.replace("\\","\\\\").replace("\"","\\\"")
            .replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")).append("\"\n");
        // The native Java launcher reads @files using the platform encoding,
        // not Java's UTF-8 default. Preserve non-ASCII Windows Maven-cache paths.
        Files.writeString(arguments,text,Charset.forName(System.getProperty("native.encoding","UTF-8")));
        // Disable further expansion before main so a nested child's @file reaches
        // ProcessBuilder literally instead of expanding into another long command.
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments));
    }
    public static final class Fixture { public static void main(String[] args) throws Exception {Thread.sleep(Long.parseLong(args[0]));} }
    public static final class Initiator {
        public static void main(String[] args)throws Exception {
            Path root=Path.of(args[0]).getParent();
            var command=java(root,CrawlWorker.class.getName(),args[0],args[1],args[2]);
            command.addAll(java(root,Fixture.class.getName(),"30000"));
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
        Process parent=new ProcessBuilder(java(root,Initiator.class.getName(),lock.toString(),ready.toString(),"4")).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
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
        var firstCommand=java(root,CrawlWorker.class.getName(),lock.toString(),ready.toString(),"3");firstCommand.addAll(java(root,Fixture.class.getName(),"30000"));
        Process first=new ProcessBuilder(firstCommand).redirectErrorStream(true).redirectOutput(root.resolve("first.log").toFile()).start();
        try {
            await(ready);Path secondReady=root.resolve("second-ready.json");
            var secondCommand=java(root,CrawlWorker.class.getName(),lock.toString(),secondReady.toString(),"1");secondCommand.addAll(java(root,Fixture.class.getName(),"30000"));
            Process second=new ProcessBuilder(secondCommand).redirectErrorStream(true).redirectOutput(root.resolve("second.log").toFile()).start();
            assertTrue(second.waitFor(5,TimeUnit.SECONDS));assertNotEquals(0,second.exitValue());assertFalse(Files.exists(secondReady));
            assertTrue(first.waitFor(12,TimeUnit.SECONDS));assertEquals(124,first.exitValue());CrawlWorker.requireAvailable(lock);
        } finally {first.destroy();first.waitFor(10,TimeUnit.SECONDS);}
    }
    public static final class ArgumentFixture {
        public static void main(String[] args) throws Exception {Files.writeString(Path.of(args[0]),args[1]);}
    }
    @Test public void longClasspathAndLiteralArgumentsLaunchFromDirectoryWithSpaces() throws Exception {
        Path root=temporary.newFolder("launcher directory with spaces").toPath(),output=root.resolve("round trip.txt");
        StringBuilder classpath=new StringBuilder(System.getProperty("java.class.path"));
        while(classpath.length()<40000) classpath.append(File.pathSeparator).append(root);
        String value="@not-an-argument-file \"quoted\" C:\\fixture\\path #value";
        Process child=new ProcessBuilder(javaWithClasspath(root,classpath.toString(),ArgumentFixture.class.getName(),output.toString(),value))
            .redirectErrorStream(true).redirectOutput(root.resolve("launcher.log").toFile()).start();
        try {
            assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            assertEquals(Files.readString(root.resolve("launcher.log"),
                Charset.forName(System.getProperty("native.encoding","UTF-8"))),0,child.exitValue());
            assertEquals(value,Files.readString(output));
        } finally {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
    }
}
