package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import dev.onionall.brawler.tools.OnionSmoke;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Loopback wire checks; durable transaction/restart evidence belongs to Frontier conformance. */
public class CrawlAdmissionServiceTest {
    private static final String URL = "http://" + OnionSmoke.HOST + "/";
    private static final String TOKEN = "a".repeat(32);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
        MethodDescriptor.<BytesValue, BytesValue>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    static Map<String, Object> plan() {
        return new LinkedHashMap<>(Map.of("plan_id", UUID.randomUUID().toString(), "origin",
            URL.substring(0, URL.length() - 1), "seeds", List.of(URL), "max_pages", 3, "depth", 2,
            "approved", true));
    }

    static Map<String, Object> session(Map<String, Object> plan) {
        return Map.of("outcome", "SESSION_OPEN", "reserved_count", 1, "sealed", false, "spec",
            Map.of("session_id", plan.get("plan_id"), "origin", plan.get("origin"), "seeds",
                plan.get("seeds"), "max_pages", plan.get("max_pages"), "max_depth", plan.get("depth"),
                "approved", true, "policy_version", 1));
    }

    static Map<String, Object> reservation(String id, String url) {
        return new LinkedHashMap<>(Map.of("outcome", "RESERVED", "reservation_id",
            UUID.nameUUIDFromBytes((id + url).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
            "session_id", id, "url", url, "depth", 0, "source_url", "", "enqueued", true));
    }

    interface Handler {
        Map<String, Object> reply(Map<String, Object> request) throws Exception;
    }

    static final class Fixture implements AutoCloseable {
        private final Server server;
        Fixture(Handler handler) throws IOException {
            var service = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(COMMAND, ServerCalls.asyncUnaryCall((request, reply) -> {
                    try {
                        Map<String, Object> input = JSON.readValue(request.getValue().toByteArray(), Map.class);
                        reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(
                            JSON.writeValueAsBytes(handler.reply(input)))).build());
                        reply.onCompleted();
                    } catch (Throwable e) {
                        reply.onError(io.grpc.Status.INTERNAL.withCause(e).asRuntimeException());
                    }
                })).build();
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(service).build().start();
        }
        Map<String, Object> runtime() {
            return Map.of("frontier_host", "127.0.0.1", "frontier_port", server.getPort());
        }
        public void close() throws InterruptedException {
            assertTrue(server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void unapprovedOrMismatchedPlanIsRejectedBeforeOpeningAnyRpc() throws Exception {
        for (Object approved : Arrays.asList(false, null, "true")) {
            var plan = plan();
            plan.put("approved", approved);
            IOException error = assertThrows(IOException.class, () -> CrawlAdmissionService.admit(
                (String) plan.get("plan_id"), plan, Map.of(), TOKEN));
            assertTrue(error.getMessage(), error.getMessage().contains("approval"));
        }
        var plan = plan();
        IOException error = assertThrows(IOException.class, () -> CrawlAdmissionService.admit(
            UUID.randomUUID().toString(), plan, Map.of(), TOKEN));
        assertTrue(error.getMessage(), error.getMessage().contains("identity"));
    }

    @Test public void reservationIdAloneCannotProveCommittedSeedIdentity() throws Exception {
        var plan = plan();
        String id = (String) plan.get("plan_id");
        Map<String, Object> valid = reservation(id, URL);
        var invalid = new ArrayList<Map<String, Object>>();
        invalid.add(null);
        for (String key : List.of("outcome", "reservation_id", "session_id", "url", "depth", "source_url", "enqueued")) {
            var missing = new LinkedHashMap<>(valid);
            missing.remove(key);
            invalid.add(missing);
        }
        for (var change : Map.<String, Object>of("outcome", "SESSION_NOT_FOUND", "session_id", "other",
                "url", URL + "other", "depth", 1, "source_url", URL, "enqueued", false).entrySet()) {
            var wrong = new LinkedHashMap<>(valid);
            wrong.put(change.getKey(), change.getValue());
            invalid.add(wrong);
        }
        for (var readback : invalid) {
            try (var fixture = new Fixture(request -> switch ((String) request.get("action")) {
                case "open", "session" -> session(plan);
                case "reserve" -> valid;
                case "reservation" -> readback;
                default -> throw new AssertionError(request);
            })) {
                assertThrows("Must reject " + readback, IOException.class,
                    () -> CrawlAdmissionService.admit(id, plan, fixture.runtime(), TOKEN));
            }
        }
    }

    @Test public void finalSessionMustStillHaveSuccessfulOutcome() throws Exception {
        var plan = plan();
        String id = (String) plan.get("plan_id");
        try (var fixture = new Fixture(request -> {
            String action = (String) request.get("action");
            if (action.equals("reserve") || action.equals("reservation")) return reservation(id, URL);
            var reply = new LinkedHashMap<>(session(plan));
            if (action.equals("session")) reply.put("outcome", "SESSION_NOT_FOUND");
            return reply;
        })) {
            assertThrows(IOException.class, () -> CrawlAdmissionService.admit(id, plan, fixture.runtime(), TOKEN));
        }
    }

    @Test public void nullResponsesFailClosedAtEveryAdmissionStage() throws Exception {
        var plan = plan();
        String id = (String) plan.get("plan_id");
        for (String stage : List.of("open", "reserve", "reservation", "session")) {
            try (var fixture = new Fixture(request -> {
                String action = (String) request.get("action");
                if (action.equals(stage)) return null;
                return action.equals("reserve") || action.equals("reservation")
                    ? reservation(id, URL) : session(plan);
            })) {
                assertThrows(stage, IOException.class,
                    () -> CrawlAdmissionService.admit(id, plan, fixture.runtime(), TOKEN));
            }
        }
    }

    @Test public void partialAdmissionResumesUsingSameReservationsWithoutRollback() throws Exception {
        var plan = plan();
        plan.put("seeds", List.of(URL, URL + "second"));
        String id = (String) plan.get("plan_id");
        var stored = new LinkedHashMap<String, Map<String, Object>>();
        var actions = new ArrayList<String>();
        boolean[] fail = {true};
        try (var fixture = new Fixture(request -> {
            String action = (String) request.get("action");
            actions.add(action);
            if (action.equals("open") || action.equals("session")) return session(plan);
            String url = (String) request.get("url");
            if (action.equals("reservation")) return stored.get(url);
            if (action.equals("reserve")) {
                // Simulate a commit followed by a lost response on the second seed.
                boolean existing = stored.containsKey(url);
                var committed = stored.computeIfAbsent(url, key -> reservation(id, key));
                if (url.endsWith("second") && fail[0]) {
                    fail[0] = false;
                    throw new IOException("Lost acknowledgement");
                }
                var reply = new LinkedHashMap<>(committed);
                if (existing) reply.put("outcome", "ALREADY_RESERVED");
                return reply;
            }
            throw new AssertionError(request);
        })) {
            assertThrows(IOException.class, () -> CrawlAdmissionService.admit(id, plan, fixture.runtime(), TOKEN));
            var before = new LinkedHashMap<>(stored);
            assertEquals(2, stored.size());
            assertEquals(session(plan), CrawlAdmissionService.admit(id, plan, fixture.runtime(), TOKEN));
            assertEquals(before, stored);
            assertEquals(List.of("open", "reserve", "reservation", "reserve", "open", "reserve",
                "reservation", "reserve", "reservation", "session"), actions);
        }
    }
}
