package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import io.grpc.MethodDescriptor;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.Outlink;
import org.apache.stormcrawler.parse.ParseResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Full reservation wire records and state assertions; durable server proofs remain separate. */
public class DurableDiscoveryAdmissionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = dev.onionall.brawler.tools.OnionSmoke.HOST;
    private static final String URL = "http://" + HOST + "/";
    private static final String CHILD = URL + "child";
    private static final String TOKEN = "synthetic-discovery-admission-capability-000000";
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
        MethodDescriptor.<BytesValue, BytesValue>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    private final class Fixture implements AutoCloseable {
        final String session = UUID.randomUUID().toString();
        final Map<String, Map<String, Object>> committed = new ConcurrentHashMap<>();
        final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
        final Server server;
        final Map<String, Object> conf = new HashMap<>();
        volatile Function<Map<String, Object>, Map<String, Object>> behavior = this::normal;

        Fixture() throws Exception {
            committed.put(URL, reservation(URL, "", 0));
            var service = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(COMMAND, ServerCalls.asyncUnaryCall((request, output) -> {
                    try {
                        Map<String, Object> input = JSON.readValue(request.getValue().toByteArray(), new TypeReference<>() {});
                        if (!session.equals(input.get("session_id"))) {
                            throw new IllegalArgumentException("Fixture session mismatch");
                        }
                        calls.add(Map.copyOf(input));
                        Map<String, Object> reply = behavior.apply(input);
                        output.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(reply))).build());
                        output.onCompleted();
                    } catch (StatusRuntimeException expected) {
                        output.onError(expected);
                    } catch (Exception failure) {
                        output.onError(Status.INTERNAL.withDescription("Owned fixture contract failure").asRuntimeException());
                    }
                })).build();
            var auth = new ServerInterceptor() {
                @Override public <Q, S> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, S> call, io.grpc.Metadata headers, ServerCallHandler<Q, S> next
                ) {
                    if (!TOKEN.equals(headers.get(io.grpc.Metadata.Key.of(
                        "x-onionall-admission-token", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))) {
                        call.close(Status.PERMISSION_DENIED, new io.grpc.Metadata());
                        return new ServerCall.Listener<>() {};
                    }
                    return next.startCall(call, headers);
                }
            };
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(service, auth)).build().start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, TOKEN);
            conf.put("discovery.budget.mode", "durable-v1");
            conf.put("tor.mode", "ONLY");
            conf.put("tor.socks.host", "127.0.0.1");
            conf.put("tor.socks.port", 1);
            conf.put("tor.allowed.hosts", List.of(HOST));
            conf.put("admission.host", "127.0.0.1");
            conf.put("admission.port", server.getPort());
            conf.put("admission.session.id", session);
            conf.put("archive.job.id", session);
            conf.put("urlfrontier.crawlid", session);
            conf.put("admission.token.file", token.toString());
        }

        Map<String, Object> reservation(String url, String source, Number depth) {
            return Map.of("outcome", "RESERVED", "session_id", session, "url", url,
                "reservation_id", UUID.randomUUID().toString(), "source_url", source,
                "depth", depth, "enqueued", true);
        }

        Map<String, Object> normal(Map<String, Object> request) {
            String url = (String) request.get("url");
            if ("reservation".equals(request.get("action"))) {
                return committed.getOrDefault(url, Map.of("outcome", "RESERVATION_NOT_FOUND"));
            }
            if (!"reserve".equals(request.get("action"))) {
                throw new IllegalArgumentException("Unexpected fixture action");
            }
            Map<String, Object> offered = reservation(url, (String) request.get("source_url"), (Number) request.get("depth"));
            Map<String, Object> previous = committed.putIfAbsent(url, offered);
            return previous == null ? offered : changed(previous, "outcome", "ALREADY_RESERVED");
        }

        DurableDiscoveryFilter filter() {
            return filter(System::nanoTime);
        }

        DurableDiscoveryFilter filter(LongSupplier clock) {
            var filter = new DurableDiscoveryFilter(clock);
            filter.configure(conf, JSON.createObjectNode(), "DurableAdmission");
            return filter;
        }

        List<String> actions() {
            return calls.stream().map(call -> call.get("action") + ":" + URI.create((String) call.get("url")).getPath()).toList();
        }

        @Override public void close() throws InterruptedException {
            assertTrue("Fixture server must terminate", server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Map<String, Object> changed(Map<String, Object> record, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(record);
        result.put(key, value);
        return result;
    }

    private static ParseResult parse() {
        var result = new ParseResult(new ArrayList<>(List.of(new Outlink("http://legacy.invalid/never-enqueue"))));
        result.set(URL, new Metadata());
        return result;
    }

    private static byte[] html(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> dispositions(ParseResult result) throws Exception {
        List<String> values = new ArrayList<>();
        for (String edge : result.get(URL).getMetadata().getValues("discovery.edges")) {
            values.add((String) JSON.readValue(edge, Map.class).get("disposition"));
        }
        return values;
    }

    private static void assertIncomplete(ParseResult result) {
        Metadata md = result.get(URL).getMetadata();
        assertEquals("false", md.getFirstValue("discovery.processing.complete"));
        assertEquals("true", md.getFirstValue("onionall.discovery.retry"));
        assertEquals("ADMISSION_UNRESOLVED", md.getFirstValue("discovery.error"));
        assertNull(md.getValues("discovery.edges"));
        assertNull(md.getFirstValue("archive.committed"));
    }

    private record Invalid(String field, Object value) { }

    @Test public void malformedAuthoritativeParentsCannotMutateChildren() throws Exception {
        try (var fixture = new Fixture()) {
            List<Invalid> malformed = List.of(
                new Invalid("depth", 0.5), new Invalid("depth", -1), new Invalid("depth", 2147483648L),
                new Invalid("depth", null), new Invalid("depth", "0"), new Invalid("depth", 6),
                new Invalid("session_id", UUID.randomUUID().toString()), new Invalid("url", CHILD),
                new Invalid("reservation_id", "bad-id"), new Invalid("enqueued", false),
                new Invalid("enqueued", "true"), new Invalid("source_url", CHILD));
            for (Invalid invalid : malformed) {
                fixture.calls.clear();
                fixture.behavior = request -> changed(fixture.normal(request), invalid.field(), invalid.value());
                var result = parse();
                assertThrows("Malformed parent " + invalid.field() + "=" + invalid.value(), IllegalStateException.class,
                    () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
                assertIncomplete(result);
                assertEquals(List.of("reservation:/"), fixture.actions());
                assertEquals(1, fixture.committed.size());
                assertFalse(fixture.committed.containsKey(CHILD));
            }
            for (Map<String, Object> parent : List.of(
                fixture.reservation(URL, "http://foreign.invalid/source", 1),
                fixture.reservation(URL, URL, 1), fixture.reservation(URL, "", 1))) {
                fixture.calls.clear();
                fixture.behavior = request -> parent;
                var result = parse();
                assertThrows(IllegalStateException.class,
                    () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
                assertIncomplete(result);
                assertEquals(List.of("reservation:/"), fixture.actions());
                assertEquals(1, fixture.committed.size());
            }
        }
    }

    @Test public void conflictingNewReservationRepliesRemainFailuresAfterRemoteCommit() throws Exception {
        for (Invalid invalid : List.of(new Invalid("source_url", URL + "another-parent"), new Invalid("depth", 2),
            new Invalid("session_id", UUID.randomUUID().toString()), new Invalid("url", URL + "wrong"),
            new Invalid("reservation_id", "invalid"), new Invalid("enqueued", false))) {
            try (var fixture = new Fixture()) {
                fixture.behavior = request -> {
                    Map<String, Object> reply = fixture.normal(request);
                    return "reserve".equals(request.get("action")) ? changed(reply, invalid.field(), invalid.value()) : reply;
                };
                var result = parse();
                assertThrows(IllegalStateException.class,
                    () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
                assertIncomplete(result);
                assertEquals(List.of("reservation:/", "reserve:/child"), fixture.actions());
                assertEquals(2, fixture.committed.size());
                assertEquals(URL, fixture.committed.get(CHILD).get("source_url"));
                assertEquals(1, fixture.committed.get(CHILD).get("depth"));
            }
        }
    }

    @Test public void successReplyRequiresMatchingImmutableReservationReadback() throws Exception {
        for (Invalid invalid : List.of(new Invalid("reservation_id", UUID.randomUUID().toString()),
            new Invalid("source_url", URL + "another-parent"), new Invalid("depth", 2),
            new Invalid("session_id", UUID.randomUUID().toString()), new Invalid("url", URL + "wrong"),
            new Invalid("enqueued", false))) {
            try (var fixture = new Fixture()) {
                fixture.behavior = request -> {
                    Map<String, Object> reply = fixture.normal(request);
                    return "reservation".equals(request.get("action")) && CHILD.equals(request.get("url"))
                        ? changed(reply, invalid.field(), invalid.value()) : reply;
                };
                var result = parse();
                assertThrows(IllegalStateException.class,
                    () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
                assertIncomplete(result);
                assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
                assertEquals(2, fixture.committed.size());
                assertEquals(URL, fixture.committed.get(CHILD).get("source_url"));
                assertEquals(1, fixture.committed.get(CHILD).get("depth"));
            }
        }
    }

    @Test public void alreadyReservedRetainsFirstParentOrSeedProvenanceUnchanged() throws Exception {
        for (boolean seed : List.of(false, true)) {
            try (var fixture = new Fixture()) {
                Map<String, Object> first = fixture.reservation(CHILD, seed ? "" : URL + "original-parent", seed ? 0 : 2);
                fixture.committed.put(CHILD, first);
                var result = parse();
                fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result);
                assertEquals(List.of("ALREADY_RESERVED"), dispositions(result));
                assertEquals("true", result.get(URL).getMetadata().getFirstValue("discovery.processing.complete"));
                assertNull(result.get(URL).getMetadata().getFirstValue("onionall.discovery.retry"));
                assertTrue(result.getOutlinks().isEmpty());
                assertSame("Existing authority record must not be replaced", first, fixture.committed.get(CHILD));
                assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
                assertEquals(2, fixture.committed.size());
            }
        }
    }

    @Test public void lostAcknowledgementAfterCommitIsReconciledWithoutRepeatingMutation() throws Exception {
        for (boolean historical : List.of(false, true)) {
            try (var fixture = new Fixture()) {
                Map<String, Object> first = fixture.reservation(CHILD, URL + "original-parent", 2);
                if (historical) fixture.committed.put(CHILD, first);
                fixture.behavior = request -> {
                    Map<String, Object> reply = fixture.normal(request);
                    if ("reserve".equals(request.get("action"))) {
                        throw Status.UNAVAILABLE.withDescription("Owned lost acknowledgement after commit").asRuntimeException();
                    }
                    return reply;
                };
                var result = parse();
                fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result);
                assertEquals(List.of("RESERVED"), dispositions(result));
                assertEquals("true", result.get(URL).getMetadata().getFirstValue("discovery.processing.complete"));
                assertTrue(result.getOutlinks().isEmpty());
                assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
                assertEquals(2, fixture.committed.size());
                if (historical) assertSame("Reconciliation retains immutable first provenance", first, fixture.committed.get(CHILD));
            }
        }
    }

    @Test public void negativeOrUnavailableReconciliationFailsWithoutAnotherReserve() throws Exception {
        for (boolean committed : List.of(false, true)) {
            try (var fixture = new Fixture()) {
                fixture.behavior = request -> {
                    if ("reserve".equals(request.get("action"))) {
                        if (committed) fixture.normal(request);
                        throw Status.UNAVAILABLE.withDescription("Owned ambiguous reserve").asRuntimeException();
                    }
                    if (committed && CHILD.equals(request.get("url"))) {
                        throw Status.UNAVAILABLE.withDescription("Owned unavailable readback").asRuntimeException();
                    }
                    return fixture.normal(request);
                };
                var result = parse();
                var failure = assertThrows(IllegalStateException.class,
                    () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
                assertTrue(failure.getCause().getMessage().contains("UNKNOWN"));
                assertIncomplete(result);
                assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
                assertEquals(committed, fixture.committed.containsKey(CHILD));
                assertEquals(committed ? 2 : 1, fixture.committed.size());
            }
        }
    }

    @Test public void timeBudgetExhaustionAfterCommitPublishesIncompleteIntentAndStopsFurtherMutations() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.conf.put("discovery.admission.timeout.ms", 1000);
            AtomicLong clock = new AtomicLong();
            fixture.behavior = request -> {
                Map<String, Object> reply = fixture.normal(request);
                if ("reserve".equals(request.get("action"))) clock.set(TimeUnit.SECONDS.toNanos(1) + 1);
                return reply;
            };
            var result = parse();
            assertThrows(IllegalStateException.class, () -> fixture.filter(clock::get).filter(URL,
                html("<a href='/child'>child</a><a href='/second'>second</a>"), null, result));
            Metadata md = result.get(URL).getMetadata();
            assertEquals("false", md.getFirstValue("discovery.processing.complete"));
            assertEquals("TIME_BUDGET_EXHAUSTED", md.getFirstValue("discovery.error"));
            assertEquals("true", md.getFirstValue("discovery.links.truncated"));
            assertEquals("true", md.getFirstValue("onionall.discovery.retry"));
            assertNull(md.getValues("discovery.edges"));
            assertEquals(List.of("reservation:/", "reserve:/child"), fixture.actions());
            assertTrue(fixture.committed.containsKey(CHILD));
            assertFalse(fixture.committed.containsKey(URL + "second"));
            assertEquals(2, fixture.committed.size());
        }
    }

    @Test public void malformedCandidateIsDataButRpcInvalidArgumentRemainsAnObservableFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var malformed = parse();
            fixture.filter().filter(URL, html("<a href='/%ZZ'>malformed</a>"), null, malformed);
            assertEquals(List.of("INVALID_URL"), dispositions(malformed));
            assertEquals("true", malformed.get(URL).getMetadata().getFirstValue("discovery.processing.complete"));
            assertTrue(malformed.getOutlinks().isEmpty());
            assertEquals(List.of("reservation:/"), fixture.actions());
            assertEquals(1, fixture.committed.size());

            fixture.calls.clear();
            fixture.behavior = request -> {
                if ("reserve".equals(request.get("action"))) {
                    throw Status.INVALID_ARGUMENT.withDescription("Owned infrastructure contract error").asRuntimeException();
                }
                return fixture.normal(request);
            };
            var failed = parse();
            assertThrows(IllegalStateException.class,
                () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, failed));
            assertIncomplete(failed);
            assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
            assertFalse(fixture.committed.containsKey(CHILD));
            assertEquals(1, fixture.committed.size());
        }
    }

    @Test public void canonicalDefaultPortsDeduplicateAndMaximumParentDepthCannotOverflow() throws Exception {
        try (var fixture = new Fixture()) {
            var result = parse();
            fixture.filter().filter(URL, html("<a href='http://" + HOST + ":80/child'>first</a>" +
                "<a href='/child'>same</a>"), null, result);
            assertEquals(List.of("RESERVED"), dispositions(result));
            assertEquals(List.of("reservation:/", "reserve:/child", "reservation:/child"), fixture.actions());
            assertTrue(fixture.committed.containsKey(CHILD));
        }
        try (var fixture = new Fixture()) {
            fixture.committed.put(URL, fixture.reservation(URL, URL + "source", 5));
            fixture.behavior = request -> {
                if ("reserve".equals(request.get("action"))) {
                    assertEquals(6, request.get("depth"));
                    return Map.of("outcome", "DEPTH_REJECTED");
                }
                return fixture.normal(request);
            };
            var result = parse();
            fixture.filter().filter(URL, html("<a href='/child'>outside depth</a>"), null, result);
            assertEquals(List.of("DEPTH_REJECTED"), dispositions(result));
            assertEquals("true", result.get(URL).getMetadata().getFirstValue("discovery.processing.complete"));
            assertFalse(fixture.committed.containsKey(CHILD));
        }
    }

    @Test public void slowCommittedRpcIsCancelledByRemainingPageBudgetWithoutMutationRetry() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.conf.put("discovery.admission.timeout.ms", 1000);
            CountDownLatch committed = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            AtomicLong offeredDeadline = new AtomicLong(Long.MAX_VALUE);
            fixture.behavior = request -> {
                var reply = fixture.normal(request);
                if ("reserve".equals(request.get("action"))) {
                    offeredDeadline.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                    Context.current().addListener(context -> cancelled.countDown(), Runnable::run);
                    committed.countDown();
                    try {
                        assertTrue("Client deadline must cancel the held acknowledgement",
                            cancelled.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return reply;
            };
            var result = parse();
            assertThrows(IllegalStateException.class,
                () -> fixture.filter().filter(URL, html("<a href='/child'>child</a>"), null, result));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertTrue("Page budget must shorten the default 10-second RPC", offeredDeadline.get() <= 1000);
            assertEquals(1, fixture.calls.stream().filter(call -> "reserve".equals(call.get("action"))).count());
            assertTrue(fixture.committed.containsKey(CHILD));
            assertEquals("false", result.get(URL).getMetadata().getFirstValue("discovery.processing.complete"));
            assertEquals("true", result.get(URL).getMetadata().getFirstValue("onionall.discovery.retry"));
            assertNull(result.get(URL).getMetadata().getValues("discovery.edges"));
        }
    }
}
