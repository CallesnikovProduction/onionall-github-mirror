package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.tools.OnionSmoke;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Characterizes an OPEN defect. PASS means reproduced, not safe process recovery. */
public class WorkerCrashCharacterizationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final class IdleChild {
        public static void main(String[] args)throws Exception {
            try(var store=new ArchiveStore(Path.of(args[0]))){store.job(args[1]);}
            // Mimics an idle interval between catalog callbacks, retaining an owned port.
            try(var listener=new java.net.ServerSocket()) {
                listener.bind(new InetSocketAddress("127.0.0.1",0));
                Path ready=Path.of(args[2]), pending=ready.resolveSibling(ready.getFileName()+".pending");
                JSON.writeValue(pending.toFile(),Map.of("pid",ProcessHandle.current().pid(),"port",listener.getLocalPort()));
                Files.move(pending,ready,StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(60000);
            }
        }
    }
    private static Map<String,Object> read(Path p)throws Exception {
        return JSON.readValue(Files.readAllBytes(p),new TypeReference<Map<String,Object>>(){});
    }
    @Test public void forcedOwnerDeathLeavesChildAndCurrentGuardReachesAdmissionTripwire()throws Exception {
        Path root=temporary.newFolder().toPath(),archive=root.resolve("archive"),work=root.resolve("work");String id;
        try(var store=new ArchiveStore(archive)) {
            id=(String)store.createPlan(List.of("http://"+OnionSmoke.HOST+"/"),1,3,"owned-process-fixture").get("plan_id");store.approvePlan(id);
        }
        Path job=work.resolve(id);Files.createDirectories(job);
        Path lock=archive.resolve("crawl-worker.lock"),ready=job.resolve("owner-ready.json"),childReady=job.resolve("child-ready.json");
        List<String> command=new ArrayList<>(List.of(lock.toString(),ready.toString(),"30"));
        command.addAll(CrawlWorkerTest.java(root,IdleChild.class.getName(),archive.toString(),id,childReady.toString()));
        Process owner=new ProcessBuilder(CrawlWorkerTest.java(root,CrawlWorker.class.getName(),command.toArray(String[]::new)))
            .redirectErrorStream(true).redirectOutput(root.resolve("owner.log").toFile()).start();
        ProcessHandle child=null;Instant childStart=null;
        Server tripwire=null;
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while((!Files.exists(ready)||!Files.exists(childReady))&&owner.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(Files.exists(ready));assertTrue(Files.exists(childReady));
            Map<String,Object> ownerInfo=read(ready),childInfo=read(childReady);
            assertEquals(owner.pid(),((Number)ownerInfo.get("owner_pid")).longValue());
            long pid=((Number)childInfo.get("pid")).longValue();assertEquals(pid,((Number)ownerInfo.get("worker_pid")).longValue());
            child=ProcessHandle.of(pid).orElseThrow();childStart=child.info().startInstant().orElseThrow();
            String ownerStart=owner.info().startInstant().orElseThrow().toString();
            JSON.writeValue(job.resolve("execution.json").toFile(),Map.of("plan_id",id,"worker_owner_pid",owner.pid(),"worker_pid",pid,
                "worker_owner_start_instant",ownerStart,"worker_start_instant",childStart.toString(),"process_scope",WorkerObservation.scope(),"state","RUNNING"));
            owner.destroyForcibly();assertTrue(owner.waitFor(5,TimeUnit.SECONDS));
            assertTrue("Known child survived forced wrapper death",child.isAlive());
            assertEquals(childStart,child.info().startInstant().orElseThrow());
            var observation=WorkerObservation.read(job.resolve("execution.json"),archive,id);
            assertEquals(Boolean.FALSE,observation.details().get("owner_alive"));
            assertEquals(Boolean.TRUE,observation.details().get("child_alive"));
            assertEquals(Boolean.FALSE,observation.details().get("ownership_lock_held"));
            assertEquals(Boolean.TRUE,observation.worker().alive());
            try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",((Number)childInfo.get("port")).intValue()),1000);}
            CrawlWorker.requireAvailable(lock); // Existing barrier accepts despite live child.
            AtomicInteger reached=new AtomicInteger();
            var method=MethodDescriptor.<BytesValue,BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
                .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
            var definition=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission").addMethod(method,
                ServerCalls.asyncUnaryCall((request,reply)->{reached.incrementAndGet();reply.onError(Status.FAILED_PRECONDITION.withDescription("Owned admission tripwire").asRuntimeException());})).build();
            tripwire=io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(definition).build().start();
            Path runtime=root.resolve("runtime.json"),token=root.resolve("token"),jar=root.resolve("empty-fixture.jar");
            var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
            try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest)) { }
            Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
            JSON.writeValue(runtime.toFile(),Map.of("frontier_host","127.0.0.1","frontier_port",tripwire.getPort(),"work_directory",work.toString(),
                "storm_executable",executable.toString(),"crawler_jar",jar.toString(),"socks_host","127.0.0.1","socks_port",19050));
            Files.writeString(token,"synthetic-crash-admission-tripwire-0000");
            IOException failure=assertThrows(IOException.class,()->CrawlExecution.start(archive,id,runtime,token));
            assertTrue(failure.getMessage().contains("FAILED_PRECONDITION"));
            assertEquals("Current start reached admission despite surviving child",1,reached.get());
            assertTrue(child.isAlive());
            Path evidenceRoot=Path.of(System.getProperty("basedir",".")).resolve(".runtime/worker-crash-characterization");
            Files.createDirectories(evidenceRoot);Path evidence=Files.createTempDirectory(evidenceRoot,"case-");
            Map<String,Object> proof=new LinkedHashMap<>();proof.put("finding","REC-09 OPEN");
            proof.put("platform",System.getProperty("os.name"));proof.put("jdk",System.getProperty("java.version"));
            proof.put("observed_at",Instant.now().toString());proof.put("owner_exit_code",owner.exitValue());
            proof.put("owner_alive",false);proof.put("child_alive",true);proof.put("lock_held",false);
            proof.put("child_listener_reachable",true);proof.put("admission_tripwire_calls",reached.get());
            proof.put("second_crawler_launched",false);proof.put("frontier_mutated",false);
            JSON.writeValue(evidence.resolve("proof.json").toFile(),proof);
            try(var input=Files.newInputStream(root.resolve("owner.log"))){Files.write(evidence.resolve("owner.log"),input.readNBytes(65536));}
            System.out.println("REC-09 retained evidence: "+evidence.toAbsolutePath());
            System.out.println("REC-09 CONFIRMED in JVM fixture: forced owner exit, live child/listener, released lock, admission reached; no second crawler/admission mutation executed");
        } finally {
            if(tripwire!=null)tripwire.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
            if(owner.isAlive()){owner.destroy();if(!owner.waitFor(5,TimeUnit.SECONDS)){owner.destroyForcibly();owner.waitFor(5,TimeUnit.SECONDS);}}
            if(child!=null&&child.isAlive()) {
                assertEquals("Never terminate a reused/foreign PID",childStart,child.info().startInstant().orElseThrow());
                child.destroyForcibly();child.onExit().get(10,TimeUnit.SECONDS);
            }
        }
    }
}
