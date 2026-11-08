package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual parser callback + loopback wire contract. Durable quota proof is Frontier conformance. */
public class DurableDiscoveryFilterTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String HOST=dev.onionall.brawler.tools.OnionSmoke.HOST, URL="http://"+HOST+"/a";
    private static final String TOKEN="synthetic-filter-capability-00000000";
    private static final MethodDescriptor<BytesValue,BytesValue> COMMAND=MethodDescriptor.<BytesValue,BytesValue>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
        .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
    private final class Fixture implements AutoCloseable {
        final Server server;final Map<String,Object> conf;final String job=UUID.randomUUID().toString();
        final List<Map<String,Object>> calls=Collections.synchronizedList(new ArrayList<>());
        volatile Function<Map<String,Object>,Map<String,Object>> reply=request->reservation(job,(String)request.get("url"),0,"");
        Fixture()throws Exception {
            var service=ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission").addMethod(COMMAND,
                ServerCalls.asyncUnaryCall((request,output)->{
                    try {
                        Map<String,Object> value=JSON.readValue(request.getValue().toByteArray(),new TypeReference<Map<String,Object>>(){});
                        assertEquals(job,value.get("session_id"));calls.add(value);
                        output.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(reply.apply(value)))).build());output.onCompleted();
                    }catch(Throwable e){output.onError(Status.INTERNAL.withDescription("Controlled fixture failure").withCause(e).asRuntimeException());}
                })).build();
            var auth=new ServerInterceptor(){public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,io.grpc.Metadata headers,ServerCallHandler<Q,S> next) {
                if(!TOKEN.equals(headers.get(io.grpc.Metadata.Key.of("x-onionall-admission-token",io.grpc.Metadata.ASCII_STRING_MARSHALLER)))) {
                    call.close(Status.PERMISSION_DENIED,new io.grpc.Metadata());return new ServerCall.Listener<>(){};
                }
                return next.startCall(call,headers);
            }};
            server=io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0))
                .addService(ServerInterceptors.intercept(service,auth)).build().start();
            Path token=temporary.newFile().toPath();Files.writeString(token,TOKEN);
            conf=new HashMap<>();conf.put("discovery.budget.mode","durable-v1");conf.put("tor.mode","ONLY");
            conf.put("tor.socks.host","127.0.0.1");conf.put("tor.socks.port",1);conf.put("tor.allowed.hosts",List.of(HOST));
            conf.put("admission.host","127.0.0.1");conf.put("admission.port",server.getPort());conf.put("admission.session.id",job);
            conf.put("archive.job.id",job);conf.put("urlfrontier.crawlid",job);conf.put("admission.token.file",token.toString());
        }
        DurableDiscoveryFilter filter() {var filter=new DurableDiscoveryFilter();filter.configure(conf,JSON.createObjectNode(),"DurableAdmission");return filter;}
        public void close() {
            server.shutdownNow();
            try {assertTrue("Fixture gRPC server must terminate",server.awaitTermination(5,TimeUnit.SECONDS));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError("Fixture cleanup interrupted",e);}
        }
    }
    private static Map<String,Object> reservation(String id,String url,int depth,String source) {
        return new LinkedHashMap<>(Map.of("outcome","RESERVED","session_id",id,"url",url,
            "reservation_id",UUID.nameUUIDFromBytes((id+url).getBytes(StandardCharsets.UTF_8)).toString(),
            "depth",depth,"source_url",source,"enqueued",true));
    }
    private static ParseResult parse() {
        var result=new ParseResult(new ArrayList<>(List.of(new Outlink("http://legacy.invalid/never-enqueue"))));
        result.set(URL,new Metadata());return result;
    }
    private static byte[] html(String body){return body.getBytes(StandardCharsets.UTF_8);}
    private static List<Map<String,Object>> edges(ParseResult result)throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String row:result.get(URL).getMetadata().getValues("discovery.edges"))rows.add(JSON.readValue(row,new TypeReference<Map<String,Object>>(){}));
        return rows;
    }
    @Test public void graphOrderAndRejectionsRemainDataWhileLegacyOutlinksAreCleared()throws Exception {
        try(var fixture=new Fixture()) {
            Map<String,String> outcomes=Map.of("/b","RESERVED","/again","ALREADY_RESERVED","/deep","DEPTH_REJECTED","/limit","LIMIT_REJECTED","/dump.zip","METADATA_ONLY");
            fixture.reply=request->{
                String target=(String)request.get("url");
                if(target.equals(URL)) return reservation(fixture.job,URL,1,"http://"+HOST+"/");
                String outcome=outcomes.get(URI.create(target).getPath());
                if(!Set.of("RESERVED","ALREADY_RESERVED").contains(outcome)) return Map.of("outcome",outcome);
                var reply=reservation(fixture.job,target,outcome.equals("ALREADY_RESERVED")?0:2,
                    outcome.equals("ALREADY_RESERVED")?"":URL);
                if(request.get("action").equals("reserve"))reply.put("outcome",outcome);
                return reply;
            };
            var result=parse();fixture.filter().filter(URL,html("<base href='http://foreign.invalid/'><a href='/b'>B</a><a href='/b#dup'>dup</a><a href='/again'>again</a><a href='/deep'>deep</a><a href='/limit'>limit</a><a href='/dump.zip'>metadata</a><a href='http://foreign.invalid/escape'>foreign</a>"),null,result);
            assertTrue(result.getOutlinks().isEmpty());assertEquals(8,fixture.calls.size());
            assertEquals("reservation",fixture.calls.getFirst().get("action"));
            var reserveCalls=fixture.calls.stream().filter(c->c.get("action").equals("reserve")).toList();
            assertEquals(List.of("/b","/again","/deep","/limit","/dump.zip"),reserveCalls.stream().map(c->java.net.URI.create((String)c.get("url")).getPath()).toList());
            for(var call:reserveCalls){assertEquals("reserve",call.get("action"));assertEquals(2,call.get("depth"));assertEquals(URL,call.get("source_url"));}
            var rows=edges(result);assertEquals(List.of("RESERVED","ALREADY_RESERVED","DEPTH_REJECTED","LIMIT_REJECTED","METADATA_ONLY","SCOPE_REJECTED"),rows.stream().map(r->r.get("disposition")).toList());
            for(var row:rows){assertEquals(URL,row.get("source_url"));assertEquals(fixture.job,row.get("job_id"));assertNotNull(row.get("discovered_at"));}
            assertEquals("1",result.get(URL).getMetadata().getFirstValue("discovery.depth"));
        }
    }
    @Test public void replayReconcilesUsingServerOutcomeRatherThanLocalQuotaOrLegacyEnqueue()throws Exception {
        try(var fixture=new Fixture()) {
            Map<String,Map<String,Object>> reservations=new HashMap<>();
            fixture.reply=request->{
                String target=(String)request.get("url");
                if(target.equals(URL))return reservation(fixture.job,URL,0,"");
                if(request.get("action").equals("reservation"))return reservations.get(target);
                boolean duplicate=reservations.containsKey(target);
                var committed=reservations.computeIfAbsent(target,key->reservation(fixture.job,key,1,URL));
                var reply=new LinkedHashMap<>(committed);
                if(duplicate)reply.put("outcome","ALREADY_RESERVED");
                return reply;
            };
            var filter=fixture.filter();
            for(String expected:List.of("RESERVED","ALREADY_RESERVED")) {
                var result=parse();filter.filter(URL,html("<a href='/b'>b</a><a href='/b#duplicate'>dup</a>"),null,result);
                assertEquals(expected,edges(result).getFirst().get("disposition"));assertTrue(result.getOutlinks().isEmpty());
            }
            assertEquals(1,reservations.size());assertEquals(6,fixture.calls.size());
            assertEquals(List.of("reservation","reserve","reservation","reservation","reserve","reservation"),fixture.calls.stream().map(c->c.get("action")).toList());
        }
    }
    @Test public void missingParentAndUnexpectedOrUnavailableReplyThrowBeforeSuccessPublication()throws Exception {
        try(var fixture=new Fixture()) {
            var filter=fixture.filter();
            for(String failure:List.of("parent","outcome","rpc")) {
                fixture.calls.clear();fixture.reply=request->{
                    if(failure.equals("rpc"))throw new IllegalStateException("owned RPC fault");
                    if(request.get("action").equals("reservation"))return failure.equals("parent")?Map.of("outcome","RESERVATION_NOT_FOUND"):reservation(fixture.job,URL,0,"");
                    return Map.of("outcome","FUTURE_UNKNOWN_OUTCOME");
                };
                var result=parse();assertThrows(IllegalStateException.class,()->filter.filter(URL,html("<a href='/b'>b</a>"),null,result));
                assertNull(result.get(URL).getMetadata().getValues("discovery.edges"));assertFalse(result.getOutlinks().isEmpty());
                assertEquals(failure.equals("outcome")?2:1,fixture.calls.size());
            }
        }
    }
    @Test public void invalidSessionOrOversizedContentCannotContactAdmission()throws Exception {
        try(var fixture=new Fixture()) {
            fixture.conf.put("archive.job.id",UUID.randomUUID().toString());assertThrows(IllegalArgumentException.class,fixture::filter);
            fixture.conf.put("archive.job.id",fixture.job);
            Object token=fixture.conf.remove("admission.token.file");assertThrows(IllegalArgumentException.class,fixture::filter);
            fixture.conf.put("admission.token.file",token);var filter=fixture.filter();
            assertThrows(IllegalArgumentException.class,()->filter.filter(URL,new byte[131073],null,parse()));
            assertTrue(fixture.calls.isEmpty());
        }
    }

    @Test public void capabilityChangesAfterConfigurationCannotUseCapturedCredentials() throws Exception {
        try (var fixture = new Fixture()) {
            var filter = fixture.filter();
            Path file = Path.of((String) fixture.conf.get("admission.token.file"));
            Files.delete(file);
            var missing = parse();
            var error = assertThrows(IllegalStateException.class,
                () -> filter.filter(URL, html("<p>no links</p>"), null, missing));
            assertTrue(fixture.calls.isEmpty());
            assertFalse(missing.getOutlinks().isEmpty());
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                assertFalse(cause.toString().contains(file.toString()));
                assertFalse(cause.toString().contains(TOKEN));
            }
            Files.writeString(file, "b".repeat(32));
            assertThrows(IllegalStateException.class,
                () -> filter.filter(URL, html("<p>no links</p>"), null, parse()));
            assertTrue(fixture.calls.isEmpty());
            Files.writeString(file, TOKEN);
            var restored = parse();
            filter.filter(URL, html("<p>no links</p>"), null, restored);
            assertEquals(1, fixture.calls.size());
            assertTrue(restored.getOutlinks().isEmpty());
        }
    }
}
