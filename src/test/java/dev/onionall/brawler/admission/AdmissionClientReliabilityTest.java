package dev.onionall.brawler.admission;

import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual loopback RPC failures; durable commit/recovery is exercised by Frontier conformance. */
public class AdmissionClientReliabilityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String TOKEN = "synthetic-rpc-reliability-capability-000000";
    private static final String ROTATED = "synthetic-rotated-rpc-capability-0000000000";
    private static final Metadata.Key<String> HEADER = Metadata.Key.of(
        "x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER);
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
        MethodDescriptor.<BytesValue, BytesValue>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    interface Handler {
        void handle(BytesValue request, StreamObserver<BytesValue> reply);
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> credentials = new CopyOnWriteArrayList<>();
        private final Server server;

        Fixture(Handler handler) throws IOException {
            var definition = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(COMMAND, ServerCalls.asyncUnaryCall((request, reply) -> {
                    calls.incrementAndGet();
                    handler.handle(request, reply);
                })).build();
            var auth = new ServerInterceptor() {
                public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call,
                        Metadata headers, ServerCallHandler<Q, S> next) {
                    String token = headers.get(HEADER);
                    credentials.add(token == null ? "MISSING" : token);
                    return next.startCall(call, headers);
                }
            };
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(definition, auth)).build().start();
        }

        int port() { return server.getPort(); }

        public void close() throws InterruptedException {
            assertTrue(server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void respond(StreamObserver<BytesValue> reply, byte[] bytes) {
        reply.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(bytes)).build());
        reply.onCompleted();
    }

    private static void respond(StreamObserver<BytesValue> reply, String text) {
        respond(reply, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void noSecretInOrdinaryDiagnostic(Throwable failure) {
        StringWriter diagnostic = new StringWriter();
        failure.printStackTrace(new PrintWriter(diagnostic));
        assertFalse(diagnostic.toString(), diagnostic.toString().contains(TOKEN));
        assertFalse(diagnostic.toString(), diagnostic.toString().contains(ROTATED));
    }

    @Test public void transportFailurePreservesCodeAndExplicitOriginalWhileOrdinaryCauseChainIsSanitized() throws Exception {
        for (Status.Code code : List.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
                Status.Code.PERMISSION_DENIED)) {
            try (var fixture = new Fixture((request, reply) -> reply.onError(
                    Status.fromCode(code).withDescription("untrusted echo " + TOKEN).asRuntimeException()));
                 var client = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN)) {
                int expected = 0;
                for (String action : List.of("open", "reserve", "seal", "session", "reservation", "list", "counts")) {
                    var failure = assertThrows(AdmissionClient.RpcFailure.class,
                        () -> client.command(Map.of("action", action, "session_id", "s")));
                    assertEquals(AdmissionClient.FailureKind.TRANSPORT, failure.kind());
                    assertEquals(code, failure.statusCode());
                    assertEquals(Set.of("open", "reserve", "seal").contains(action), failure.mutationOutcomeUnknown());
                    assertNotNull(failure.transportFailure());
                    assertEquals(code, failure.transportFailure().getStatus().getCode());
                    assertTrue(failure.transportFailure().getStatus().getDescription().contains(TOKEN));
                    assertNotSame(failure.transportFailure(), failure.getCause());
                    assertTrue(failure.getCause() instanceof StatusRuntimeException);
                    assertNull(((StatusRuntimeException) failure.getCause()).getStatus().getDescription());
                    noSecretInOrdinaryDiagnostic(failure);
                    assertEquals("No retry, including transparent retry of mutating operations", ++expected, fixture.calls.get());
                }
                assertEquals(Collections.nCopies(expected, TOKEN), fixture.credentials);
            }
        }
    }

    @Test public void authoritativeBusinessRejectionRemainsAReplyRatherThanUnknownTransportFailure() throws Exception {
        for (String outcome : List.of("SESSION_CONFLICT", "LIMIT_REJECTED", "APPROVAL_REQUIRED")) {
            try (var fixture = new Fixture((request, reply) -> respond(reply, "{\"outcome\":\"" + outcome + "\"}"));
                 var client = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN)) {
                assertEquals(Map.of("outcome", outcome), client.command(Map.of("action", "reserve", "session_id", "s")));
                assertEquals(1, fixture.calls.get());
            }
        }
    }

    @Test public void malformedPayloadsHaveProtocolClassificationWithoutParserOrCredentialLeakage() throws Exception {
        List<byte[]> invalid = new ArrayList<>();
        for (String text : List.of("{\"outcome\":\"SESSION_OPEN\",\"echo\":\"" + TOKEN + "\"",
                "{\"outcome\":\"SESSION_OPEN\",\"outcome\":\"SESSION_CONFLICT\",\"echo\":\"" + TOKEN + "\"}",
                "{\"outcome\":\"SESSION_OPEN\"} {\"echo\":\"" + TOKEN + "\"}",
                "null", "[]", "{\"outcome\":null}", "{\"outcome\":\"lowercase\"}", "x".repeat(65537))) {
            invalid.add(text.getBytes(StandardCharsets.UTF_8));
        }
        invalid.add(new byte[]{(byte) 0xc3, 0x28});
        invalid.add("{\"outcome\":\"SESSION_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_16BE));
        invalid.add("{\"outcome\":\"SESSION_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_16LE));
        for (byte[] bytes : invalid) {
            try (var fixture = new Fixture((request, reply) -> respond(reply, bytes));
                 var client = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN)) {
                for (String action : List.of("reserve", "session")) {
                    var failure = assertThrows(AdmissionClient.RpcFailure.class,
                        () -> client.command(Map.of("action", action, "session_id", "s")));
                    assertEquals(AdmissionClient.FailureKind.PROTOCOL, failure.kind());
                    assertEquals(action.equals("reserve"), failure.mutationOutcomeUnknown());
                    assertNull(failure.statusCode());
                    assertNull(failure.transportFailure());
                    assertNull("Untrusted parser payload must not survive as a cause", failure.getCause());
                    noSecretInOrdinaryDiagnostic(failure);
                }
                assertEquals(2, fixture.calls.get());
            }
        }
    }

    @Test public void exactResponseBoundIsAcceptedAndOversizedRequestNeverDispatches() throws Exception {
        String prefix = "{\"outcome\":\"SESSION_NOT_FOUND\",\"padding\":\"", suffix = "\"}";
        String bounded = prefix + "x".repeat(65536 - prefix.length() - suffix.length()) + suffix;
        assertEquals(65536, bounded.getBytes(StandardCharsets.UTF_8).length);
        try (var fixture = new Fixture((request, reply) -> respond(reply, bounded));
             var client = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN)) {
            assertEquals("SESSION_NOT_FOUND", client.command(Map.of("action", "session")).get("outcome"));
            assertEquals(1, fixture.calls.get());
            var invalid = assertThrows(IOException.class,
                () -> client.command(Map.of("action", "reserve", "padding", TOKEN.repeat(1000))));
            assertFalse(invalid instanceof AdmissionClient.RpcFailure);
            noSecretInOrdinaryDiagnostic(invalid);
            assertNull(invalid.getCause());
            assertEquals(1, fixture.calls.get());
            assertThrows(IOException.class, () -> client.command(null));
            assertThrows(IOException.class, () -> client.command(Map.of("action", "")));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test public void fileBackedClientReloadsRotationAndFailsClosedOnDeletionWhileStringApiIsSnapshot() throws Exception {
        Path directory = temporary.newFolder().toPath(), capability = directory.resolve("capability");
        Files.writeString(capability, TOKEN);
        try (var fixture = new Fixture((request, reply) -> respond(reply, "{\"outcome\":\"SESSION_NOT_FOUND\"}"));
             var client = AdmissionClient.fromCapabilityFile("127.0.0.1", fixture.port(), capability);
             var snapshot = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN)) {
            var query = Map.<String, Object>of("action", "session", "session_id", "s");
            client.command(query);
            Path replacement = directory.resolve("replacement");
            Files.writeString(replacement, ROTATED);
            Files.move(replacement, capability, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            client.command(query);
            snapshot.command(query);
            assertEquals(List.of(TOKEN, ROTATED, TOKEN), fixture.credentials);
            assertEquals(3, fixture.calls.get());
            Files.delete(capability);
            var failure = assertThrows(AdmissionClient.RpcFailure.class, () -> client.command(query));
            assertEquals(Status.Code.PERMISSION_DENIED, failure.statusCode());
            assertFalse(failure.mutationOutcomeUnknown());
            noSecretInOrdinaryDiagnostic(failure);
            assertEquals("Deleted credentials must not produce an anonymous RPC", 3, fixture.calls.get());
            assertEquals(List.of(TOKEN, ROTATED, TOKEN), fixture.credentials);
            var missing = assertThrows(IOException.class,
                () -> AdmissionClient.fromCapabilityFile("127.0.0.1", fixture.port(), capability));
            assertFalse(missing.getMessage().contains(capability.toString()));
            assertNull(missing.getCause());
        }
    }

    @Test public void actualDeadlineCancelsSingleMutationAndPreservesUnknownOutcome() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        try (var fixture = new Fixture((request, reply) -> {
            if (request.getValue().toStringUtf8().contains("\"reserve\"")) {
                Context.current().addListener(context -> cancelled.countDown(), Runnable::run);
                dispatched.countDown();
                // Intentionally no acknowledgement; the client deadline cancels this call.
            } else {
                respond(reply, "{\"outcome\":\"SESSION_NOT_FOUND\"}");
            }
        }); var client = new AdmissionClient("127.0.0.1", fixture.port(), TOKEN, 2000)) {
            client.command(Map.of("action", "session", "session_id", "s")); // Warm the same channel.
            var failure = assertThrows(AdmissionClient.RpcFailure.class,
                () -> client.command(Map.of("action", "reserve", "session_id", "s")));
            assertTrue(dispatched.await(5, TimeUnit.SECONDS));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.statusCode());
            assertEquals(AdmissionClient.FailureKind.TRANSPORT, failure.kind());
            assertTrue(failure.mutationOutcomeUnknown());
            assertEquals(2, fixture.calls.get());
            noSecretInOrdinaryDiagnostic(failure);
        }
    }
}
