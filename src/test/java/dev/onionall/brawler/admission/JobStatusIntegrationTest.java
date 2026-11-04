package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.tools.OnionSmoke;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class JobStatusIntegrationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String URL="http://"+OnionSmoke.HOST+"/";
    private static String plan(Path root) throws Exception {
        try(var store=new ArchiveStore(root)) {
            String id=(String)store.createPlan(List.of(URL),2,3,"test").get("plan_id");store.approvePlan(id);return id;
        }
    }
    @Test public void statusUsesReadbackAndLabelsCachedFallbackWithoutFalseComplete() throws Exception {
        Path root=temporary.newFolder().toPath(),archive=root.resolve("archive");String id=plan(archive);
        Map<String,Object> session=Map.of("outcome","SESSION_OPEN","reserved_count",3,"sealed",false,"spec",
            Map.of("session_id",id,"origin",URL.substring(0,URL.length()-1),"seeds",List.of(URL),"max_pages",3,"max_depth",2,"approved",true,"policy_version",1));
        var method=MethodDescriptor.<BytesValue,BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
        AtomicReference<Status> failure=new AtomicReference<>();
        var definition=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
            .addMethod(method,ServerCalls.asyncUnaryCall((request,reply)->{
                if(failure.get()!=null) {reply.onError(failure.get().asRuntimeException());return;}
                try {
                    String action=JSON.readTree(request.getValue().toByteArray()).path("action").asText();
                    assertTrue("Status must never mutate admission",Set.of("session","counts").contains(action));
                    Object value=session;
                    if(action.equals("counts")) value=JSON.readValue("{\"outcome\":\"STATE_COUNTS\",\"state_version\":1,\"session_id\":\""+id+"\",\"readback_at\":\"2026-11-04T00:00:00Z\",\"next_attempt_at\":\"2026-11-05T00:00:00Z\",\"counts\":{\"reserved\":3,\"pending\":0,\"held\":0,\"scheduled_wait\":1,\"unscheduled\":2,\"unknown\":0,\"done\":null,\"success\":null,\"terminal\":null,\"retry_wait\":null}}",Map.class);
                    reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(value))).build());reply.onCompleted();
                }catch(Exception e){reply.onError(e);}
            })).build();
        Server server=ServerBuilder.forPort(0).addService(definition).build().start();
        try {
            Path work=root.resolve("work");Files.createDirectories(work.resolve(id));
            Path execution=work.resolve(id).resolve("execution.json");
            JSON.writeValue(execution.toFile(),Map.of("plan_id",id,"worker_pid",ProcessHandle.current().pid(),"worker_owner_pid",ProcessHandle.current().pid(),
                "worker_start_instant",Instant.EPOCH.toString(),"worker_owner_start_instant",Instant.EPOCH.toString(),
                "process_scope",WorkerObservation.scope(),"worker_exit_code",0));
            Path runtime=root.resolve("runtime.json"),token=root.resolve("token");
            JSON.writeValue(runtime.toFile(),Map.of("frontier_host","127.0.0.1","frontier_port",server.getPort(),"work_directory",work.toString()));
            Files.writeString(token,"synthetic-status-capability-token-0000");
            Map<String,Object> first=CrawlExecution.status(archive,id,runtime,token);
            assertEquals("PAUSED",first.get("job_state"));assertEquals("INCOMPLETE",first.get("completion"));
            assertEquals("SERVER_READBACK",first.get("admission"));
            assertEquals(0,((Map<?,?>)first.get("worker")).get("exit_code"));
            assertNull(((Map<?,?>)first.get("counts")).get("terminal"));
            assertTrue(((String)first.get("resume_hint")).contains(id));
            failure.set(Status.UNAVAILABLE);
            Map<String,Object> second=CrawlExecution.status(archive,id,runtime,token);
            assertEquals("UNKNOWN",second.get("job_state"));assertEquals("UNKNOWN",second.get("completion"));
            assertEquals(true,second.get("status_stale"));assertEquals(false,second.get("authoritative_readback_current"));
            assertNull(((Map<?,?>)second.get("counts")).get("pending"));
            assertNotNull(second.get("last_authoritative_counts"));
            assertEquals(false,((Map<?,?>)second.get("scheduler")).get("reachable"));
            assertEquals(2,((List<?>)second.get("status_transitions")).size());
            failure.set(Status.DEADLINE_EXCEEDED);
            Map<String,Object> timedOut=CrawlExecution.status(archive,id,runtime,token);
            assertEquals("UNKNOWN",timedOut.get("job_state"));
            assertNull(((Map<?,?>)timedOut.get("scheduler")).get("reachable"));
            assertNull(((Map<?,?>)timedOut.get("counts")).get("pending"));
        } finally {server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
        try(var store=new ArchiveStore(archive)) {
            assertEquals("UNKNOWN",((Map<?,?>)store.admissionObservation(id).get("job_status")).get("job_state"));
        }
    }
    private static Map<String,Object> observation(String id,int index) {
        return Map.of("plan_id",id,"job_status_version",1,"job_state",index%2==0?"RUNNING":"PAUSED",
            "completion","INCOMPLETE","state_basis","TEST-"+index,"observed_at",Instant.now().toString());
    }
    @Test public void concurrentAuditIsBoundedSurvivesAdmissionWritesAndDoesNotRefreshAdmissionClock() throws Exception {
        Path archive=temporary.newFolder().toPath();String id=plan(archive);
        try(var left=new ArchiveStore(archive);var right=new ArchiveStore(archive)) {
            left.recordAdmission(id,Map.of("session_id",id,"admission","COMMITTED","state","ADMITTED"));
            Object clock=left.admissionObservation(id).get("admission_observed_at");
            var pool=Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> tasks=new ArrayList<>();
                for(int i=0;i<40;i++) { final int n=i;tasks.add(pool.submit(()->{
                    try {(n%2==0?left:right).recordJobStatus(id,observation(id,n));}catch(IOException e){throw new RuntimeException(e);}
                }));}
                for(var task:tasks)task.get(10,TimeUnit.SECONDS);
            } finally {pool.shutdownNow();}
            var old=left.admissionObservation(id);
            assertEquals(clock,old.get("admission_observed_at"));
            assertEquals(32,((List<?>)old.get("status_transitions")).size());
            assertEquals(8,((Number)old.get("status_transitions_dropped")).intValue());
            left.recordAdmission(id,Map.of("session_id",id,"admission","COMMITTED","state","WORKER_EXITED"));
            assertEquals(old.get("job_status"),left.admissionObservation(id).get("job_status"));
            assertEquals(old.get("status_transitions"),left.admissionObservation(id).get("status_transitions"));
        }
        try(var db=DriverManager.getConnection("jdbc:h2:file:"+archive.resolve("catalog")+";AUTO_SERVER=FALSE","sa","");var query=db.createStatement();var rows=query.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertTrue(rows.next());assertEquals("No persistent schema migration",2,rows.getInt(1));
        }
    }
    public static final class CatalogOwner {
        public static void main(String[] args) throws Exception {
            try(var store=new ArchiveStore(Path.of(args[0]))) { Files.writeString(Path.of(args[1]),"owned");Thread.sleep(60000); }
        }
    }
    @Test public void actualStartReturnsVersionedJobViewEvenWhenLocalExecutableRejectsStormArguments() throws Exception {
        Path root=temporary.newFolder().toPath(),archive=root.resolve("archive");String id=plan(archive);
        var spec=Map.of("session_id",id,"origin",URL.substring(0,URL.length()-1),"seeds",List.of(URL),"max_pages",3,"max_depth",2,"approved",true,"policy_version",1);
        var method=MethodDescriptor.<BytesValue,BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
        String reservation=UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var service=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission").addMethod(method,ServerCalls.asyncUnaryCall((request,reply)->{
            try {
                String action=JSON.readTree(request.getValue().toByteArray()).path("action").asText();Object value;
                if(Set.of("open","session").contains(action)) value=Map.of("outcome","SESSION_OPEN","reserved_count",1,"sealed",false,"spec",spec);
                else if(Set.of("reserve","reservation").contains(action)) value=Map.of("outcome","RESERVED","reservation_id",reservation);
                else if(action.equals("counts")) value=JSON.readValue("{\"outcome\":\"STATE_COUNTS\",\"state_version\":1,\"session_id\":\""+id+"\",\"readback_at\":\"2026-11-04T00:00:00Z\",\"next_attempt_at\":\"2026-11-04T00:00:00Z\",\"counts\":{\"reserved\":1,\"pending\":1,\"held\":0,\"scheduled_wait\":0,\"unscheduled\":0,\"unknown\":0,\"done\":null,\"success\":null,\"terminal\":null,\"retry_wait\":null}}",Map.class);
                else throw new AssertionError("Unexpected mutation endpoint");
                reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(value))).build());reply.onCompleted();
            }catch(Exception e){reply.onError(e);}
        })).build();
        Server server=ServerBuilder.forPort(0).addService(service).build().start();
        try {
            Path jar=root.resolve("fixture.jar"),runtime=root.resolve("runtime.json"),token=root.resolve("token");
            var manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
            try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest)) { }
            Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
            JSON.writeValue(runtime.toFile(),Map.of("frontier_host","127.0.0.1","frontier_port",server.getPort(),"work_directory",root.resolve("work").toString(),
                "storm_executable",executable.toString(),"crawler_jar",jar.toString(),"socks_host","127.0.0.1","socks_port",19050));
            Files.writeString(token,"synthetic-start-view-capability-000000");
            var result=CrawlExecution.start(archive,id,runtime,token);
            assertEquals("COMMITTED",result.get("admission"));assertEquals(false,result.get("passed"));
            assertNotEquals(0,((Number)result.get("worker_exit_code")).intValue());
            assertEquals(1,result.get("job_status_version"));assertEquals("INCOMPLETE",result.get("completion"));
            assertTrue(Set.of("PAUSED","UNKNOWN").contains(result.get("job_state")));
            assertFalse(Boolean.TRUE.equals(result.get("crawl_completed_verified")));
        }finally{server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    }
    @Test public void secondJvmCatalogContentionIsExplicitErrorNeverEmptyOrComplete() throws Exception {
        Path root=temporary.newFolder().toPath(),archive=root.resolve("catalog"),ready=root.resolve("ready");String id=plan(archive);
        Process owner=new ProcessBuilder(CrawlWorkerTest.java(root,CatalogOwner.class.getName(),archive.toString(),ready.toString()))
            .redirectErrorStream(true).redirectOutput(root.resolve("catalog-owner.log").toFile()).start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.exists(ready)&&owner.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue("Owned catalog fixture failed to become ready",Files.exists(ready));
            assertThrows(IOException.class,()->CrawlExecution.status(archive,id,null,null));
        } finally {owner.destroy();if(!owner.waitFor(5,TimeUnit.SECONDS)){owner.destroyForcibly();assertTrue(owner.waitFor(5,TimeUnit.SECONDS));}}
        assertEquals("APPROVED",CrawlExecution.status(archive,id,null,null).get("job_state"));
    }
}
