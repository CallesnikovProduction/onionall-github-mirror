package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import dev.onionall.brawler.archive.ArchiveStore;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.task.IOutputCollector;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.bolt.JSoupParserBolt;
import org.apache.stormcrawler.persistence.Status;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Installed parser and generated filter chain; loopback state is not a durable Frontier proof. */
public class ParserDiscoveryFailureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = dev.onionall.brawler.tools.OnionSmoke.HOST;
    private static final String URL = "http://" + HOST + "/";
    private static final String FIRST = URL + "first";
    private static final String SECOND = URL + "second";
    private static final String TOKEN = "synthetic-parser-discovery-capability-00000000";
    private static final byte[] HTML = ("<html><title>Owned parser discovery</title><body>" +
        "<a href='/first'>first</a><a href='/second'>second</a>" +
        "<a href='http://legacy.invalid/never-enqueue'>foreign</a></body></html>").getBytes(StandardCharsets.UTF_8);
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
        MethodDescriptor.<BytesValue, BytesValue>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    private static final class Context extends TopologyContext {
        Context(Map<String, Object> conf, Path root) {
            super(new StormTopology(), conf, Map.of(1, "parse"), Map.of("parse", List.of(1)), Map.of(), Map.of(),
                "owned-parser-discovery", root.toString(), root.toString(), 1, 6700, List.of(1),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), null);
        }

        @Override public <T extends IMetric> T registerMetric(String name, T metric, int seconds) {
            return metric;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Server server;
        final String job;
        final Map<String, Map<String, Object>> committed = new ConcurrentHashMap<>();
        final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
        final AtomicBoolean failSecond = new AtomicBoolean(true);

        Fixture(String job) throws Exception {
            this.job = job;
            committed.put(URL, reservation(URL, "", 0));
            var service = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(COMMAND, ServerCalls.asyncUnaryCall((request, output) -> {
                    try {
                        Map<String, Object> input = JSON.readValue(request.getValue().toByteArray(), new TypeReference<>() {});
                        if (!job.equals(input.get("session_id"))) {
                            throw new IllegalArgumentException("Fixture session mismatch");
                        }
                        String target = (String) input.get("url");
                        Map<String, Object> response;
                        if ("reservation".equals(input.get("action"))) {
                            response = committed.getOrDefault(target, Map.of("outcome", "RESERVATION_NOT_FOUND"));
                        } else if ("reserve".equals(input.get("action"))) {
                            if (SECOND.equals(target) && failSecond.get()) {
                                output.onError(io.grpc.Status.UNAVAILABLE.withDescription("Owned second reserve fault").asRuntimeException());
                                return;
                            }
                            Map<String, Object> proposed = reservation(target, (String) input.get("source_url"), (Number) input.get("depth"));
                            Map<String, Object> previous = committed.putIfAbsent(target, proposed);
                            response = new LinkedHashMap<>(previous == null ? proposed : previous);
                            response.put("outcome", previous == null ? "RESERVED" : "ALREADY_RESERVED");
                        } else {
                            throw new IllegalArgumentException("Unexpected fixture action");
                        }
                        replies.add(Map.copyOf(response));
                        output.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(response))).build());
                        output.onCompleted();
                    } catch (Exception failure) {
                        output.onError(io.grpc.Status.INTERNAL.withDescription("Owned fixture contract failure").asRuntimeException());
                    }
                })).build();
            var auth = new ServerInterceptor() {
                @Override public <Q, S> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, S> call, io.grpc.Metadata headers, ServerCallHandler<Q, S> next
                ) {
                    if (!TOKEN.equals(headers.get(io.grpc.Metadata.Key.of(
                        "x-onionall-admission-token", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))) {
                        call.close(io.grpc.Status.PERMISSION_DENIED, new io.grpc.Metadata());
                        return new ServerCall.Listener<>() {};
                    }
                    return next.startCall(call, headers);
                }
            };
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(service, auth)).build().start();
        }

        private Map<String, Object> reservation(String url, String source, Number depth) {
            return Map.of("outcome", "RESERVED", "session_id", job, "url", url, "source_url", source,
                "depth", depth, "reservation_id", UUID.randomUUID().toString(), "enqueued", true);
        }

        @Override public void close() throws InterruptedException {
            server.shutdownNow();
            assertTrue("Fixture server must terminate", server.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class Attempt {
        final Metadata metadata = new Metadata();
        final List<String> events = new ArrayList<>();
        final List<Status> statuses = new ArrayList<>();
        final Tuple tuple;

        Attempt() {
            metadata.setValue("fetch.statusCode", "200");
            metadata.setValue("protocol.content-type", "text/html");
            metadata.setValue("protocol.Content-Type", "text/html");
            tuple = (Tuple) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Tuple.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStringByField" -> URL;
                    case "getBinaryByField" -> HTML;
                    case "getValueByField" -> metadata;
                    default -> throw new AssertionError("Unexpected tuple method: " + method.getName());
                });
        }

        OutputCollector collector() {
            IOutputCollector output = (IOutputCollector) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IOutputCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("emit")) {
                        String stream = (String) args[0];
                        var anchors = (java.util.Collection<?>) args[1];
                        assertEquals("Each parser output must be anchored", 1, anchors.size());
                        assertSame(tuple, anchors.iterator().next());
                        events.add("emit:" + stream);
                        var values = (List<?>) args[2];
                        if (stream.equals("status")) {
                            assertEquals(3, values.size());
                            assertEquals(URL, values.get(0));
                            assertSame(metadata, values.get(1));
                            statuses.add((Status) values.get(2));
                        } else {
                            assertEquals("default", stream);
                            assertEquals(4, values.size());
                            assertEquals(URL, values.get(0));
                            assertSame(metadata, values.get(2));
                            assertEquals("Archive must commit before emission", "true", metadata.getFirstValue("archive.committed"));
                        }
                        return List.of();
                    }
                    if (method.getName().equals("ack") || method.getName().equals("fail")) {
                        assertSame(tuple, args[0]);
                        events.add(method.getName());
                        return null;
                    }
                    throw new AssertionError("Unexpected collector method: " + method.getName());
                });
            return new OutputCollector(output);
        }
    }

    @Test public void partialAdmissionFailureStopsParserOutputAndManualReplayKeepsCommittedChildren() throws Exception {
        Path root = temporary.newFolder().toPath();
        Map<String, Object> plan;
        String job;
        try (var store = new ArchiveStore(root)) {
            job = (String) store.createPlan(List.of(URL), 1, 3, "parser-discovery-fixture").get("plan_id");
            store.approvePlan(job);
            plan = store.plan(job);
        }
        try (var fixture = new Fixture(job)) {
            Path token = temporary.newFile().toPath();
            Files.writeString(token, TOKEN);
            Path workRoot = temporary.newFolder().toPath();
            Path work = workRoot.resolve(job);
            Path original = workRoot.resolve("installed.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(original))) {
                jar.putNextEntry(new JarEntry("owned-fixture.txt"));
                jar.write(new byte[] {1});
                jar.closeEntry();
            }
            Path generated = CrawlRuntimePreparer.prepare(plan, Map.of("frontier_host", "127.0.0.1",
                "frontier_port", fixture.server.getPort(), "socks_host", "127.0.0.1", "socks_port", 1,
                "work_directory", workRoot.toString()),
                root, token, work, original, 1000, 120);
            Map<String, Object> envelope = JSON.readValue(work.resolve("crawler-conf.json").toFile(), new TypeReference<>() {});
            @SuppressWarnings("unchecked") Map<String, Object> configuration = new HashMap<>((Map<String, Object>) envelope.get("config"));
            Map<String, Object> filterConfiguration = new LinkedHashMap<>();
            // Load the generated, packaged chain rather than restating its class names in this test.
            try (var jar = new JarFile(generated.toFile())) {
                for (String key : List.of("parsefilters.config.file", "urlfilters.config.file")) {
                    try (var input = jar.getInputStream(jar.getJarEntry((String) configuration.get(key)))) {
                        filterConfiguration.putAll(JSON.readValue(input, new TypeReference<Map<String, Object>>() {}));
                    }
                }
            }
            filterConfiguration.put("org.apache.stormcrawler.parse.JSoupFilters", List.of());
            String resource = "owned-parser-discovery-" + UUID.randomUUID() + ".json";
            Path filters = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).resolve(resource);
            Files.write(filters, JSON.writeValueAsBytes(filterConfiguration), StandardOpenOption.CREATE_NEW);
            configuration.put("parsefilters.config.file", resource);
            configuration.put("urlfilters.config.file", resource);
            configuration.put("jsoupfilters.config.file", resource);
            configuration.put("detect.mimetype", false);
            configuration.put("stormcrawler.metrics.version", "v1");
            try {
                Attempt failed = new Attempt();
                execute(configuration, root, failed);
                assertEquals(List.of("emit:status", "ack"), failed.events);
                assertEquals(List.of(Status.ERROR), failed.statuses);
                assertEquals("content filtering", failed.metadata.getFirstValue("error.source"));
                assertNull(failed.metadata.getFirstValue("archive.committed"));
                assertNull(failed.metadata.getValues("discovery.edges"));
                assertTrue(fixture.committed.containsKey(FIRST));
                assertFalse(fixture.committed.containsKey(SECOND));
                Map<String, Object> committedFirst = fixture.committed.get(FIRST);
                try (var store = new ArchiveStore(root)) {
                    assertEquals(0L, store.job(job).get("snapshots"));
                    assertEquals(0L, store.job(job).get("archived_pages"));
                }

                fixture.failSecond.set(false);
                fixture.replies.clear();
                Attempt replay = new Attempt();
                execute(configuration, root, replay);
                assertEquals(List.of("emit:default", "ack"), replay.events);
                assertTrue("No legacy outlink or FETCHED status may be emitted by parser", replay.statuses.isEmpty());
                assertEquals("true", replay.metadata.getFirstValue("archive.committed"));
                assertEquals(committedFirst, fixture.committed.get(FIRST));
                assertTrue(fixture.committed.containsKey(SECOND));
                assertTrue(fixture.replies.stream().anyMatch(row -> FIRST.equals(row.get("url")) &&
                    "ALREADY_RESERVED".equals(row.get("outcome"))));
                List<String> dispositions = new ArrayList<>();
                for (String edge : replay.metadata.getValues("discovery.edges")) {
                    dispositions.add((String) JSON.readValue(edge, Map.class).get("disposition"));
                }
                assertEquals(List.of("ALREADY_RESERVED", "RESERVED", "SCOPE_REJECTED"), dispositions);
                try (var store = new ArchiveStore(root)) {
                    assertEquals(1L, store.job(job).get("snapshots"));
                    assertEquals(1L, store.job(job).get("archived_pages"));
                }
            } finally {
                Files.deleteIfExists(filters);
            }
        }
    }

    private static void execute(Map<String, Object> configuration, Path root, Attempt attempt) {
        JSoupParserBolt parser = new JSoupParserBolt();
        try {
            parser.prepare(configuration, new Context(configuration, root), attempt.collector());
            parser.execute(attempt.tuple);
        } finally {
            parser.cleanup();
        }
    }
}
