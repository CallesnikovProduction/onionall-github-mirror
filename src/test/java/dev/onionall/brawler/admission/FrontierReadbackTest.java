package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrontierReadbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "synthetic-client-readback-capability-0000";
    private static Map<String,Object> decode(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<Map<String,Object>>(){});
    }
    private static Map<String,Object> counts() throws Exception {
        return decode("{\"outcome\":\"STATE_COUNTS\",\"state_version\":1,\"session_id\":\"s\",\"readback_at\":\"2026-11-04T00:00:00Z\","
            + "\"next_attempt_at\":\"2026-11-05T00:00:00Z\",\"counts\":{\"reserved\":5,\"pending\":1,\"held\":1,\"scheduled_wait\":1,"
            + "\"unscheduled\":2,\"unknown\":0,\"done\":null,\"success\":null,\"terminal\":null,\"retry_wait\":null}}");
    }
    private static String cursor(int through, int after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:s:" + through + ":" + after).getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,Object> row(int ordinal) {
        Map<String,Object> scheduler = new LinkedHashMap<>(); scheduler.put("state", "UNSCHEDULED");
        scheduler.put("next_due_epoch", null); scheduler.put("lease_until_epoch", null);
        return new LinkedHashMap<>(Map.of("session_id", "s", "ordinal", ordinal, "reservation_id", UUID.nameUUIDFromBytes(new byte[]{(byte)ordinal}).toString(),
            "url", "http://fixture.invalid/" + ordinal, "source_url", "", "depth", 0, "scheduler", scheduler));
    }
    private static Map<String,Object> page() {
        Map<String,Object> page = new LinkedHashMap<>(Map.of("outcome", "STATE_LIST", "state_version", 1, "session_id", "s",
            "readback_at", "2026-11-04T00:00:00Z", "through_ordinal", 3, "rows", List.of(row(1), row(2))));
        page.put("next_cursor", cursor(3, 2)); return page;
    }
    @Test public void unknownSuccessRemainsNullAndScheduledWaitIsNotInventedRetry() throws Exception {
        var result = FrontierReadback.counts(counts(), "s");
        assertEquals(5, result.reserved()); assertEquals(2, result.unscheduled());
        assertNull(result.success()); assertNull(result.terminal()); assertNull(result.done()); assertNull(result.retryWait());
        assertNotNull(result.nextAttemptAt());
        var malformed = counts(); malformed.put("outcome", "UNKNOWN_FUTURE_OUTCOME");
        assertThrows(IOException.class, () -> FrontierReadback.counts(malformed, "s"));
        var wrong = counts(); wrong.put("state_version", 2);
        assertThrows(IOException.class, () -> FrontierReadback.counts(wrong, "s"));
        var falseDone = counts(); ((Map<String,Object>)falseDone.get("counts")).put("done", 2);
        assertThrows(IOException.class, () -> FrontierReadback.counts(falseDone, "s"));
        var partial = counts(); ((Map<String,Object>)partial.get("counts")).remove("held");
        assertThrows(IOException.class, () -> FrontierReadback.counts(partial, "s"));
        var fractional = counts(); ((Map<String,Object>)fractional.get("counts")).put("held", 1.5);
        assertThrows(IOException.class, () -> FrontierReadback.counts(fractional, "s"));
    }
    @Test public void pagesRejectMissingRowsUnknownStateAndMovingWatermark() throws Exception {
        var first = FrontierReadback.page(page(), "s", null, 2);
        assertEquals(List.of(1, 2), first.rows().stream().map(FrontierReadback.Row::ordinal).toList());
        Map<String,Object> last = page(); last.put("rows", List.of(row(3))); last.put("next_cursor", null);
        assertEquals(3, FrontierReadback.page(last, "s", first.nextCursor(), 2).rows().getFirst().ordinal());
        var missing = page(); missing.put("rows", List.of(row(1), row(3)));
        assertThrows(IOException.class, () -> FrontierReadback.page(missing, "s", null, 2));
        var truncated = page(); truncated.put("next_cursor", null);
        assertThrows(IOException.class, () -> FrontierReadback.page(truncated, "s", null, 2));
        var future = page(); Map<String,Object> r = row(1); ((Map<String,Object>)r.get("scheduler")).put("state", "DONE"); future.put("rows", List.of(r));
        assertThrows(IOException.class, () -> FrontierReadback.page(future, "s", null, 2));
        assertThrows(IOException.class, () -> FrontierReadback.page(last, "s", cursor(4, 2), 2));
    }
    @Test public void actualClientUsesExistingAuthenticatedCommandAndFailsExplicitlyOnDeadline() throws Exception {
        var method = MethodDescriptor.<BytesValue,BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();
        AtomicInteger calls = new AtomicInteger();
        var service = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
            .addMethod(method, ServerCalls.asyncUnaryCall((request, reply) -> {
                try {
                    calls.incrementAndGet(); var c = JSON.readTree(request.getValue().toByteArray());
                    assertEquals("s", c.path("session_id").asText());
                    if (calls.get() > 2) { reply.onError(Status.DEADLINE_EXCEEDED.asRuntimeException()); return; }
                    Object result = c.path("action").asText().equals("list") ? page() : counts();
                    reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(JSON.writeValueAsBytes(result))).build()); reply.onCompleted();
                } catch (Exception e) { reply.onError(e); }
            })).build();
        ServerInterceptor auth = new ServerInterceptor() {
            public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call, Metadata headers, ServerCallHandler<Q,S> next) {
                assertEquals(TOKEN, headers.get(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER)));
                return next.startCall(call, headers);
            }
        };
        Server server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service, auth)).build().start();
        try (var client = new AdmissionClient("127.0.0.1", server.getPort(), TOKEN)) {
            assertEquals(5, client.counts("s").reserved());
            assertEquals(2, client.list("s", null, 2).rows().size());
            IOException failure = assertThrows(IOException.class, () -> client.counts("s"));
            assertTrue(failure.getMessage().contains("DEADLINE_EXCEEDED"));
            assertFalse(failure.getMessage().contains(TOKEN)); assertEquals(3, calls.get());
            assertThrows(IOException.class, () -> client.list("s", null, 201)); assertEquals(3, calls.get());
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
}
