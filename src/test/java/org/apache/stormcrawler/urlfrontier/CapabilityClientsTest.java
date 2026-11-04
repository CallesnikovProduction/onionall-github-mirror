package org.apache.stormcrawler.urlfrontier;

import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.*;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real channels and the actual pinned worker components; no Tor or external target. */
public class CapabilityClientsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String TOKEN_A="synthetic-capability-aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN_B="synthetic-capability-bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String URL="http://fixture.invalid/seed";

    private static void await(String message,BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(message,condition.getAsBoolean());
    }
    private static class MetricsContext extends TopologyContext {
        MetricsContext(Map<String,Object> conf) {
            super(new StormTopology(),conf,Map.of(1,"fixture"),Map.of("fixture",List.of(1)),
                Map.of(),Map.of(),"fixture","","",1,0,List.of(1),Map.of(),Map.of(),Map.of(),
                new HashMap<>(),new AtomicBoolean(),null);
        }
        @Override public <T extends IMetric> T registerMetric(String name,T metric,int bucket) {return metric;}
    }
    private static final class ProbeSpout extends CapabilitySpout {
        void query() {populateBuffer();}
        boolean queryDone() {return !isInQuery.get();}
        boolean hasBufferedUrl() {return buffer.hasNext();}
    }
    private static final class ProbeUpdater extends CapabilityStatusUpdaterBolt {
        final List<io.grpc.Status.Code> errors=new CopyOnWriteArrayList<>();
        @Override public void onError(Throwable error) {
            errors.add(io.grpc.Status.fromThrowable(error).getCode());super.onError(error);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicReference<String> expected=new AtomicReference<>(TOKEN_A);
        final AtomicInteger attempts=new AtomicInteger(),denied=new AtomicInteger(),leases=new AtomicInteger(),streams=new AtomicInteger();
        final List<URLItem> writes=new CopyOnWriteArrayList<>();
        Server server;int port;
        void start() throws Exception {
            ServerInterceptor auth=new ServerInterceptor(){
                public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,io.grpc.Metadata headers,ServerCallHandler<Q,S> next) {
                    attempts.incrementAndGet();String token=headers.get(io.grpc.Metadata.Key.of("x-onionall-admission-token",io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                    if(!expected.get().equals(token)) {denied.incrementAndGet();call.close(io.grpc.Status.PERMISSION_DENIED,new io.grpc.Metadata());return new ServerCall.Listener<Q>(){};}
                    return next.startCall(call,headers);
                }
            };
            server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",port)).addService(ServerInterceptors.intercept(new URLFrontierGrpc.URLFrontierImplBase(){
                @Override public void getURLs(GetParams request,StreamObserver<URLInfo> out) {
                    leases.incrementAndGet();out.onNext(URLInfo.newBuilder().setCrawlID("fixture").setKey("fixture.invalid").setUrl(URL).build());out.onCompleted();
                }
                @Override public StreamObserver<URLItem> putURLs(StreamObserver<AckMessage> out) {
                    streams.incrementAndGet();return new StreamObserver<>(){
                        public void onNext(URLItem item){writes.add(item);out.onNext(AckMessage.newBuilder().setID(item.getID()).setStatus(AckMessage.Status.OK).build());}
                        public void onError(Throwable error){}
                        public void onCompleted(){out.onCompleted();}
                    };
                }
            },auth)).build().start();port=server.getPort();
        }
        void stop() throws Exception {server.shutdownNow();assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));}
        @Override public void close() throws Exception {stop();}
    }
    private static Map<String,Object> conf(Fixture fixture,Path token) {
        Map<String,Object> options=new HashMap<>();options.put("admission.token.file",token.toString());
        options.put("urlfrontier.host","127.0.0.1");options.put("urlfrontier.port",fixture.port);options.put("urlfrontier.crawlid","fixture");
        options.put("urlfrontier.max.messages.in.flight",10);options.put("urlfrontier.throttling.time.ms",1);
        options.put("urlbuffer.class","org.apache.stormcrawler.persistence.urlbuffer.SimpleURLBuffer");
        options.put("scheduler.class","org.apache.stormcrawler.persistence.DefaultScheduler");options.put("status.updater.use.cache",false);
        return options;
    }
    private static Tuple tuple() {
        return (Tuple)Proxy.newProxyInstance(CapabilityClientsTest.class.getClassLoader(),new Class<?>[]{Tuple.class},(proxy,method,args)->switch(method.getName()){
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            case "toString" -> "fixture-tuple";
            default -> throw new AssertionError(method.getName());
        });
    }
    private static OutputCollector collector(AtomicInteger ack,AtomicInteger failed) {
        return new OutputCollector((IOutputCollector)Proxy.newProxyInstance(CapabilityClientsTest.class.getClassLoader(),new Class<?>[]{IOutputCollector.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "emit":return List.of();
                case "ack":ack.incrementAndGet();return null;
                case "fail":failed.incrementAndGet();return null;
                case "flush":return null;
                default:throw new AssertionError(method.getName());
            }
        }));
    }
    private static ManagedChannel updaterChannel(CapabilityStatusUpdaterBolt updater) throws Exception {
        // Observe the real transport condition expected by the pinned upstream
        // reconnect branch; do not invoke callbacks or replace its channel/stub.
        var field=CapabilityStatusUpdaterBolt.class.getDeclaredField("channel");field.setAccessible(true);
        return (ManagedChannel)field.get(updater);
    }
    private static void awaitReconnectFailure(CapabilityStatusUpdaterBolt updater) throws Exception {
        ManagedChannel channel=updaterChannel(updater);
        await("transport observes refusal while fixture port is closed",()->channel.getState(false)==ConnectivityState.TRANSIENT_FAILURE);
    }

    @Test public void missingOrMalformedCapabilityFailsBeforeEitherClientInitializes() throws Exception {
        Path token=temporary.newFolder().toPath().resolve("capability");
        for(Map<String,Object> options:List.of(Map.<String,Object>of(),Map.<String,Object>of("admission.token.file",token.toString()))) {
            assertThrows(IllegalArgumentException.class,()->new CapabilitySpout().open(options,null,null));
            assertThrows(IllegalArgumentException.class,()->new CapabilityStatusUpdaterBolt().prepare(options,null,null));
        }
        for(String invalid:List.of("short","invalid token with spaces and sufficient length")) {
            Files.writeString(token,invalid);Map<String,Object> options=Map.of("admission.token.file",token.toString());
            assertThrows(IllegalArgumentException.class,()->new CapabilitySpout().open(options,null,null));
            assertThrows(IllegalArgumentException.class,()->new CapabilityStatusUpdaterBolt().prepare(options,null,null));
        }
    }

    @Test public void spoutAuthenticatesEveryLeaseAndFailsClosedAfterTokenChangeOrDeletion() throws Exception {
        Path token=temporary.newFolder().toPath().resolve("capability");Files.writeString(token,TOKEN_A);
        try(Fixture fixture=new Fixture()) {
            fixture.start();Map<String,Object> options=conf(fixture,token);ProbeSpout spout=new ProbeSpout();
            spout.open(options,new MetricsContext(options),new SpoutOutputCollector(null));
            try {
                spout.query();await("initial authenticated lease",()->fixture.leases.get()==1&&spout.queryDone());assertTrue(spout.hasBufferedUrl());
                Files.writeString(token,TOKEN_B);spout.query();await("wrong capability denied",()->fixture.denied.get()==1&&spout.queryDone());assertEquals(1,fixture.leases.get());
                int attempts=fixture.attempts.get();Files.delete(token);spout.query();await("missing file ends query",spout::queryDone);assertEquals(attempts,fixture.attempts.get());assertEquals(1,fixture.leases.get());
                fixture.expected.set(TOKEN_B);Files.writeString(token,TOKEN_B);spout.query();await("changed capability reread",()->fixture.leases.get()==2&&spout.queryDone());
            } finally {spout.close();}
            ProbeSpout recreated=new ProbeSpout();recreated.open(options,new MetricsContext(options),new SpoutOutputCollector(null));
            try {recreated.query();await("recreated channel authenticates",()->fixture.leases.get()==3&&recreated.queryDone());}
            finally {recreated.close();}
        }
    }

    @Test public void updaterAuthenticatesReconnectAndPreservesRetryTerminalDatesAndAcks() throws Exception {
        Path token=temporary.newFolder().toPath().resolve("capability");Files.writeString(token,TOKEN_A);
        try(Fixture fixture=new Fixture()) {
            fixture.start();Map<String,Object> options=conf(fixture,token);CapabilityStatusUpdaterBolt updater=new CapabilityStatusUpdaterBolt();
            AtomicInteger ack=new AtomicInteger(),failed=new AtomicInteger();updater.prepare(options,new MetricsContext(options),collector(ack,failed));
            try {
                await("initial authenticated write stream",()->fixture.streams.get()==1);
                Date first=Date.from(java.time.Instant.now().plusSeconds(100));updater.store(URL,Status.FETCHED,new Metadata(),Optional.of(first),tuple());
                await("first write acknowledged",()->ack.get()==1);assertEquals(first.toInstant().getEpochSecond(),fixture.writes.get(0).getKnown().getRefetchableFromDate());
                fixture.stop();awaitReconnectFailure(updater);fixture.expected.set(TOKEN_B);Files.writeString(token,TOKEN_B);fixture.start();
                await("reconnected write stream authenticates with reread token",()->fixture.streams.get()>=2);
                Date retry=Date.from(java.time.Instant.now().plusSeconds(200));updater.store(URL+"/retry",Status.FETCH_ERROR,new Metadata(),Optional.of(retry),tuple());
                updater.store(URL+"/terminal",Status.ERROR,new Metadata(),Optional.empty(),tuple());
                await("retry and terminal acknowledged",()->ack.get()==3);assertEquals(3,fixture.writes.size());
                assertEquals(retry.toInstant().getEpochSecond(),fixture.writes.get(1).getKnown().getRefetchableFromDate());assertEquals(0,fixture.writes.get(2).getKnown().getRefetchableFromDate());
                assertEquals(0,failed.get());assertEquals(0,fixture.denied.get());
            } finally {updater.cleanup();}
            Files.delete(token);assertThrows(IllegalArgumentException.class,()->new CapabilityStatusUpdaterBolt().prepare(options,new MetricsContext(options),collector(ack,failed)));
            assertEquals(3,fixture.writes.size());
        }
    }

    @Test public void updaterWrongCapabilityNeverStartsAnonymousWriteStream() throws Exception {
        Path token=temporary.newFolder().toPath().resolve("capability");Files.writeString(token,TOKEN_B);
        try(Fixture fixture=new Fixture()) {
            fixture.start();Map<String,Object> options=conf(fixture,token);CapabilityStatusUpdaterBolt updater=new CapabilityStatusUpdaterBolt();
            AtomicInteger ack=new AtomicInteger(),failed=new AtomicInteger();updater.prepare(options,new MetricsContext(options),collector(ack,failed));
            try {await("invalid capability stream denied",()->fixture.denied.get()==1);assertEquals(0,fixture.streams.get());assertTrue(fixture.writes.isEmpty());assertEquals(0,ack.get());}
            finally {updater.cleanup();}
        }
    }

    @Test public void updaterReconnectWithDeletedCapabilityFailsLocallyWithoutAnonymousStream() throws Exception {
        Path token=temporary.newFolder().toPath().resolve("capability");Files.writeString(token,TOKEN_A);
        try(Fixture fixture=new Fixture()) {
            fixture.start();Map<String,Object> options=conf(fixture,token);ProbeUpdater updater=new ProbeUpdater();
            updater.prepare(options,new MetricsContext(options),collector(new AtomicInteger(),new AtomicInteger()));
            try {
                await("initial authenticated stream",()->fixture.streams.get()==1);
                Files.delete(token);fixture.stop();awaitReconnectFailure(updater);fixture.start();
                await("deleted capability rejected during stream recreation",()->updater.errors.contains(io.grpc.Status.Code.PERMISSION_DENIED));
                assertEquals(1,fixture.streams.get());assertTrue(fixture.writes.isEmpty());assertEquals(0,fixture.denied.get());
                Files.writeString(token,TOKEN_B);fixture.expected.set(TOKEN_B);fixture.stop();awaitReconnectFailure(updater);fixture.start();
                await("restored capability authenticates later reconnect",()->fixture.streams.get()==2);
            } finally {updater.cleanup();}
        }
    }
}
