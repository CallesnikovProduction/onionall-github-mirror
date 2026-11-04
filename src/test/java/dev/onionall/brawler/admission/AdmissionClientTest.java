package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import dev.onionall.brawler.archive.ArchiveStore;
import dev.onionall.brawler.tools.OnionSmoke;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Wire contract only; admission transaction conformance uses the real server separately. */
public class AdmissionClientTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String TOKEN="a".repeat(32);
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final MethodDescriptor<BytesValue,BytesValue> COMMAND=MethodDescriptor.<BytesValue,BytesValue>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
        .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
    @Test public void authenticatedBoundedJsonUsesVersionedRpcWithoutFallback() throws Exception {
        var calls=new AtomicInteger();
        var service=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
            .addMethod(COMMAND,ServerCalls.asyncUnaryCall((request,reply)->{
                try {
                    assertEquals("session",JSON.readTree(request.getValue().toByteArray()).path("action").asText());
                    calls.incrementAndGet();
                    reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFromUtf8("{\"outcome\":\"SESSION_NOT_FOUND\"}")).build());reply.onCompleted();
                } catch(Exception e){reply.onError(e);}
            })).build();
        var interceptor=new ServerInterceptor(){public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT,RespT> call,Metadata headers,ServerCallHandler<ReqT,RespT> next){
            assertEquals(TOKEN,headers.get(Metadata.Key.of("x-onionall-admission-token",Metadata.ASCII_STRING_MARSHALLER)));return next.startCall(call,headers);
        }};
        Server server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service,interceptor)).build().start();
        try(var client=new AdmissionClient("127.0.0.1",server.getPort(),TOKEN)) {
            assertEquals("SESSION_NOT_FOUND",client.command(Map.of("action","session","session_id","x")).get("outcome"));
            assertEquals(1,calls.get());
            assertThrows(IOException.class,()->client.command(Map.of("action","x".repeat(32769))));
            assertEquals(1,calls.get());
        } finally {server.shutdownNow();server.awaitTermination();}
    }
    @Test public void unsupportedServerFailsClosedWithoutSeedFallback() throws Exception {
        Server server=ServerBuilder.forPort(0).build().start();
        try(var client=new AdmissionClient("127.0.0.1",server.getPort(),TOKEN)) {
            IOException failure=assertThrows(IOException.class,()->client.command(Map.of("action","open")));
            assertTrue(failure.getMessage().contains("UNIMPLEMENTED"));assertFalse(failure.getMessage().contains(TOKEN));
        } finally {server.shutdownNow();server.awaitTermination();}
    }
    @Test public void missingCatalogObservationIsReconciledOnlyByAuthoritativeReadback() throws Exception {
        Path root=temporary.newFolder().toPath(), archive=root.resolve("catalog");
        Map<String,Object> plan;
        try(var store=new ArchiveStore(archive)) {
            plan=store.createPlan(List.of("http://"+OnionSmoke.HOST+"/"),2,3,"test");
            store.approvePlan((String)plan.get("plan_id"));
            assertTrue(store.admissionObservation((String)plan.get("plan_id")).isEmpty());
        }
        String id=(String)plan.get("plan_id");
        Map<String,Object> committed=Map.of("outcome","SESSION_OPEN","reserved_count",1,"sealed",false,"spec",
            Map.of("session_id",id,"origin",plan.get("origin"),"seeds",plan.get("seeds"),"max_pages",3,"max_depth",2,"approved",true,"policy_version",1));
        var calls=new AtomicInteger();
        var service=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
            .addMethod(COMMAND,ServerCalls.asyncUnaryCall((request,reply)->{
                try {
                    String action=JSON.readTree(request.getValue().toByteArray()).path("action").asText();
                    assertTrue(Set.of("session","counts").contains(action));
                    Object response=committed;
                    if(action.equals("counts")) response=JSON.readValue("{\"outcome\":\"STATE_COUNTS\",\"state_version\":1,\"session_id\":\""+id+"\",\"readback_at\":\"2026-11-04T00:00:00Z\",\"next_attempt_at\":\"2026-11-04T00:00:00Z\",\"counts\":{\"reserved\":1,\"pending\":1,\"held\":0,\"scheduled_wait\":0,\"unscheduled\":0,\"unknown\":0,\"done\":null,\"success\":null,\"terminal\":null,\"retry_wait\":null}}",Map.class);
                    calls.incrementAndGet();reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(response))).build());reply.onCompleted();
                } catch(Exception e){reply.onError(e);}
            })).build();
        Server server=ServerBuilder.forPort(0).addService(service).build().start();
        try {
            Path runtime=root.resolve("runtime.json"), token=root.resolve("token");
            Files.writeString(runtime,JSON.writeValueAsString(Map.of("frontier_host","127.0.0.1","frontier_port",server.getPort())));
            Files.writeString(token,TOKEN);
            assertEquals("ADMITTED",CrawlExecution.status(archive,id,runtime,token).get("state"));
            assertEquals("ADMITTED",CrawlExecution.status(archive,id,runtime,token).get("state"));
            assertEquals(4,calls.get()); // Session/counts reads only; no open/reserve/legacy put has been issued by reconciliation.
            try(var store=new ArchiveStore(archive)) {
                Map<String,Object> observed=store.admissionObservation(id);
                assertEquals("COMMITTED",observed.get("admission"));
                assertEquals(1,((Number)((Map<?,?>)observed.get("authoritative_admission")).get("reserved_count")).intValue());
                assertEquals("URLFrontier",observed.get("admission_authority"));
            }
        } finally {server.shutdownNow();server.awaitTermination();}
    }
    @Test public void weakCapabilitiesAndInvalidEndpointsAreRejectedBeforeConnecting() {
        assertThrows(IOException.class,()->new AdmissionClient("127.0.0.1",7071,"short"));
        assertThrows(IOException.class,()->new AdmissionClient("http://wrong",7071,TOKEN));
        assertThrows(IOException.class,()->new AdmissionClient("internal.ONION.",7071,TOKEN));
    }
}
