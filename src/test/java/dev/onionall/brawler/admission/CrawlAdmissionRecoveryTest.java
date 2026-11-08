package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import dev.onionall.brawler.archive.ArchiveStore;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Client recovery/state checks; actual WAL/restart evidence is AdmissionAmbiguousRpcConformance. */
public class CrawlAdmissionRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String TOKEN = "synthetic-admission-recovery-capability-000000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
        MethodDescriptor.<BytesValue, BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
            .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    private interface Handler {
        Map<String, Object> reply(Map<String, Object> request);
    }

    private static final class Fixture implements AutoCloseable {
        final Server server;
        Fixture(Handler handler) throws IOException {
            var definition = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(COMMAND, ServerCalls.asyncUnaryCall((request, observer) -> {
                    try {
                        Map<String, Object> command = JSON.readValue(request.getValue().toByteArray(), Map.class);
                        observer.onNext(BytesValue.newBuilder().setValue(ByteString.copyFrom(
                            JSON.writeValueAsBytes(handler.reply(command)))).build());
                        observer.onCompleted();
                    } catch (StatusRuntimeException e) {
                        observer.onError(e);
                    } catch (Throwable e) {
                        observer.onError(Status.INTERNAL.withCause(e).asRuntimeException());
                    }
                })).build();
            var guard = new ServerInterceptor() {
                @Override public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call,
                    Metadata headers, ServerCallHandler<Q, S> next) {
                    if (!TOKEN.equals(headers.get(Metadata.Key.of("x-onionall-admission-token",
                        Metadata.ASCII_STRING_MARSHALLER)))) {
                        call.close(Status.PERMISSION_DENIED, new Metadata());
                        return new ServerCall.Listener<Q>() { };
                    }
                    return next.startCall(call, headers);
                }
            };
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(definition, guard)).build().start();
        }
        Map<String, Object> runtime() {
            return Map.of("frontier_host", "127.0.0.1", "frontier_port", server.getPort());
        }
        public void close() throws InterruptedException {
            assertTrue(server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** A visible authoritative-state fixture, not a durability substitute. */
    private static final class Ledger {
        final Map<String, Object> plan;
        final String id;
        final List<String> actions = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
        volatile boolean opened;
        Ledger(Map<String, Object> plan) {
            this.plan = plan; id = (String) plan.get("plan_id");
        }
        Map<String, Object> command(Map<String, Object> command) {
            String action = (String) command.get("action");
            actions.add(action);
            return perform(command);
        }
        Map<String, Object> perform(Map<String, Object> command) {
            String action = (String) command.get("action");
            switch (action) {
                case "open":
                    opened = true;
                    return session();
                case "session":
                    return opened ? session() : Map.of("outcome", "SESSION_NOT_FOUND");
                case "reserve":
                    String url = (String) command.get("url");
                    boolean duplicate = stored.containsKey(url);
                    Map<String, Object> value = stored.computeIfAbsent(url,
                        key -> CrawlAdmissionServiceTest.reservation(id, key));
                    var reply = new LinkedHashMap<>(value);
                    if (duplicate) reply.put("outcome", "ALREADY_RESERVED");
                    return reply;
                case "reservation":
                    return stored.getOrDefault(command.get("url"), Map.of("outcome", "RESERVATION_NOT_FOUND"));
                default:
                    throw new AssertionError(command);
            }
        }
        Map<String, Object> session() {
            var session = new LinkedHashMap<>(CrawlAdmissionServiceTest.session(plan));
            session.put("reserved_count", stored.size());
            return session;
        }
    }

    private static CrawlAdmissionService.AdmissionFailure failure(Ledger ledger, Fixture fixture) {
        return assertThrows(CrawlAdmissionService.AdmissionFailure.class, () ->
            CrawlAdmissionService.admit(ledger.id, ledger.plan, fixture.runtime(), TOKEN));
    }

    @Test public void lostOpenAndReserveAcknowledgementsRecoverOnlyThroughAuthoritativeReads() throws Exception {
        for (String lostAction : List.of("open", "reserve")) {
            Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
            boolean[] fail = {true};
            try (Fixture fixture = new Fixture(command -> {
                var reply = ledger.command(command);
                if (lostAction.equals(command.get("action")) && fail[0]) {
                    fail[0] = false;
                    throw Status.UNAVAILABLE.withDescription("Lost acknowledgement").asRuntimeException();
                }
                return reply;
            })) {
                var observed = CrawlAdmissionService.admit(ledger.id, ledger.plan, fixture.runtime(), TOKEN);
                assertEquals(ledger.session(), observed);
                assertTrue(ledger.opened);
                assertEquals(1, ledger.stored.size());
                assertEquals(1, Collections.frequency(ledger.actions, "open"));
                assertEquals(1, Collections.frequency(ledger.actions, "reserve"));
                assertEquals(lostAction.equals("open")
                    ? List.of("open", "session", "reserve", "reservation", "session")
                    : List.of("open", "reserve", "reservation", "reservation", "session"), ledger.actions);
                assertEquals(ledger.id, ledger.stored.values().iterator().next().get("session_id"));
            }
        }
    }

    @Test public void negativeReadbackAfterDeadlineRemainsUnknownAndNeverRetriesMutation() throws Exception {
        for (String failedAction : List.of("open", "reserve")) {
            Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
            try (Fixture fixture = new Fixture(command -> {
                if (failedAction.equals(command.get("action"))) {
                    ledger.actions.add(failedAction);
                    throw Status.DEADLINE_EXCEEDED.asRuntimeException();
                }
                return ledger.command(command);
            })) {
                var failure = failure(ledger, fixture);
                assertEquals(CrawlAdmissionService.FailureOutcome.UNKNOWN, failure.outcome());
                assertTrue(failure.getCause() instanceof AdmissionClient.RpcFailure);
                assertEquals(Status.Code.DEADLINE_EXCEEDED,
                    ((AdmissionClient.RpcFailure) failure.getCause()).statusCode());
                assertEquals(failedAction.equals("open") ? List.of("open", "session")
                    : List.of("open", "reserve", "reservation"), ledger.actions);
                assertEquals(failedAction.equals("reserve"), ledger.opened);
                assertTrue(ledger.stored.isEmpty());
            }
        }
    }

    @Test public void unavailableReadbackPreservesCommittedStateAndExplicitUnknown() throws Exception {
        for (String lostAction : List.of("open", "reserve")) {
            Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
            boolean[] lost = {false};
            try (Fixture fixture = new Fixture(command -> {
                String action = (String) command.get("action");
                ledger.actions.add(action);
                if (lost[0]) throw Status.UNAVAILABLE.asRuntimeException();
                var reply = ledger.perform(command);
                if (action.equals(lostAction)) {
                    lost[0] = true;
                    throw Status.UNAVAILABLE.asRuntimeException();
                }
                return reply;
            })) {
                var failure = failure(ledger, fixture);
                assertEquals(CrawlAdmissionService.FailureOutcome.UNKNOWN, failure.outcome());
                assertEquals(1, failure.getSuppressed().length);
                assertTrue(ledger.opened);
                assertEquals(lostAction.equals("reserve") ? 1 : 0, ledger.stored.size());
                assertEquals(lostAction.equals("open") ? List.of("open", "session")
                    : List.of("open", "reserve", "reservation"), ledger.actions);
            }
        }
    }

    @Test public void authoritativeConflictNeverReservesOrReplacesExistingSession() throws Exception {
        Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
        ledger.opened = true;
        Map<String, Object> before = ledger.session();
        try (Fixture fixture = new Fixture(command -> {
            ledger.actions.add((String) command.get("action"));
            return Map.of("outcome", "SESSION_CONFLICT");
        })) {
            assertEquals(CrawlAdmissionService.FailureOutcome.CONFLICT, failure(ledger, fixture).outcome());
            assertEquals(List.of("open"), ledger.actions);
            assertEquals(before, ledger.session());
            assertTrue(ledger.stored.isEmpty());
        }
    }

    @Test public void authoritativeRejectionDoesNotTriggerReconciliationOrRetry() throws Exception {
        for (String rejectedAction : List.of("open", "reserve")) {
            Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
            try (Fixture fixture = new Fixture(command -> {
                if (rejectedAction.equals(command.get("action"))) {
                    ledger.actions.add(rejectedAction);
                    return Map.of("outcome", rejectedAction.equals("open") ? "ADMISSION_READ_ONLY" : "LIMIT_REJECTED");
                }
                return ledger.command(command);
            })) {
                assertEquals(CrawlAdmissionService.FailureOutcome.REJECTED, failure(ledger, fixture).outcome());
                assertEquals(rejectedAction.equals("open") ? List.of("open") : List.of("open", "reserve"), ledger.actions);
                assertEquals(rejectedAction.equals("reserve"), ledger.opened);
                assertTrue(ledger.stored.isEmpty());
            }
        }
    }

    @Test public void malformedOrConflictingSuccessfulOpenCannotReachSeedMutation() throws Exception {
        for (String malformed : List.of("missing-outcome", "wrong-spec", "invalid-number")) {
            Ledger ledger = new Ledger(CrawlAdmissionServiceTest.plan());
            try (Fixture fixture = new Fixture(command -> {
                var reply = new LinkedHashMap<>(ledger.command(command));
                if (malformed.equals("missing-outcome")) reply.remove("outcome");
                else {
                    var wrong = new LinkedHashMap<>((Map<String, Object>) reply.get("spec"));
                    if (malformed.equals("invalid-number")) wrong.put("max_pages", 3.5);
                    else wrong.put("session_id", "other-session");
                    reply.put("spec", wrong);
                }
                return reply;
            })) {
                var outcome = failure(ledger, fixture).outcome();
                assertEquals(malformed.equals("wrong-spec") ? CrawlAdmissionService.FailureOutcome.CONFLICT
                    : CrawlAdmissionService.FailureOutcome.PROTOCOL_VIOLATION, outcome);
                assertEquals(List.of("open"), ledger.actions);
                assertTrue(ledger.opened);
                assertTrue(ledger.stored.isEmpty());
            }
        }
    }

    @Test public void malformedReservationReadbackBlocksFurtherSeedsWithoutErasingCommit() throws Exception {
        var plan = CrawlAdmissionServiceTest.plan();
        String seed = (String) ((List<?>) plan.get("seeds")).getFirst();
        plan.put("seeds", List.of(seed, seed + "second"));
        Ledger ledger = new Ledger(plan);
        try (Fixture fixture = new Fixture(command -> {
            var reply = ledger.command(command);
            if (command.get("action").equals("reservation")) {
                var wrong = new LinkedHashMap<>(reply); wrong.put("session_id", "other-session"); return wrong;
            }
            return reply;
        })) {
            assertEquals(CrawlAdmissionService.FailureOutcome.PROTOCOL_VIOLATION, failure(ledger, fixture).outcome());
            assertEquals(List.of("open", "reserve", "reservation"), ledger.actions);
            assertEquals(Set.of(seed), ledger.stored.keySet());
            assertEquals(ledger.id, ledger.stored.get(seed).get("session_id"));
        }
    }

    @Test public void statusCannotInferReachabilityFromLocalCredentialFailure() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        Map<String, Object> plan;
        try (var store = new ArchiveStore(archive)) {
            var input = CrawlAdmissionServiceTest.plan();
            var draft = store.createPlan((List<String>) input.get("seeds"), 2, 3, "status-credentials");
            plan = store.approvePlan((String) draft.get("plan_id"));
        }
        Path token = root.resolve("token"), runtime = root.resolve("runtime.json");
        Files.writeString(token, TOKEN);
        List<String> calls = new CopyOnWriteArrayList<>();
        try (var fixture = new CrawlAdmissionServiceTest.Fixture(request -> {
            calls.add((String) request.get("action"));
            Files.delete(token); // Session succeeded; the next RPC's credentials fail locally.
            return CrawlAdmissionServiceTest.session(plan);
        })) {
            JSON.writeValue(runtime.toFile(), fixture.runtime());
            var status = CrawlExecution.status(archive, (String) plan.get("plan_id"), runtime, token);
            assertEquals(List.of("session"), calls);
            assertEquals(false, status.get("authoritative_readback_current"));
            assertEquals("UNKNOWN", status.get("job_state"));
            assertNull(((Map<?, ?>) status.get("scheduler")).get("reachable"));
            assertNull(((Map<?, ?>) status.get("counts")).get("reserved"));
            try (var store = new ArchiveStore(archive)) {
                assertEquals("UNKNOWN", ((Map<?, ?>) store.admissionObservation(
                    (String) plan.get("plan_id")).get("job_status")).get("job_state"));
            }
        }
    }

    @Test public void unresolvedStartPersistsUnknownAndNeverLaunchesWorker() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = root.resolve("archive");
        Map<String, Object> plan;
        try (var store = new ArchiveStore(archive)) {
            var input = CrawlAdmissionServiceTest.plan();
            var draft = store.createPlan((List<String>) input.get("seeds"), 2, 3, "recovery-fixture");
            plan = store.approvePlan((String) draft.get("plan_id"));
        }
        Ledger ledger = new Ledger(plan);
        try (Fixture fixture = new Fixture(command -> {
            if (command.get("action").equals("open")) {
                ledger.actions.add("open"); throw Status.UNAVAILABLE.asRuntimeException();
            }
            return ledger.command(command);
        })) {
            Path jar = root.resolve("fixture.jar"), token = root.resolve("token"), runtimeFile = root.resolve("runtime.json");
            var manifest = new java.util.jar.Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) { }
            Files.writeString(token, TOKEN);
            if (Files.getFileStore(token).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(token, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
            var runtime = new LinkedHashMap<>(fixture.runtime());
            runtime.put("storm_executable", Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
            runtime.put("crawler_jar", jar.toString()); runtime.put("work_directory", root.resolve("work").toString());
            runtime.put("socks_host", "127.0.0.1"); runtime.put("socks_port", 19050);
            JSON.writeValue(runtimeFile.toFile(), runtime);
            var failure = assertThrows(CrawlAdmissionService.AdmissionFailure.class, () ->
                CrawlExecution.start(archive, ledger.id, runtimeFile, token));
            assertEquals(CrawlAdmissionService.FailureOutcome.UNKNOWN, failure.outcome());
            assertEquals(List.of("open", "session"), ledger.actions);
            assertFalse(ledger.opened); assertTrue(ledger.stored.isEmpty());
            Path work = root.resolve("work").resolve(ledger.id);
            Map<?, ?> checkpoint = JSON.readValue(work.resolve("admission-observation.json").toFile(), Map.class);
            assertEquals("UNKNOWN", checkpoint.get("admission"));
            assertEquals("UNKNOWN", checkpoint.get("admission_outcome"));
            assertEquals("ADMISSION_UNRESOLVED", checkpoint.get("state"));
            assertFalse(Files.exists(work.resolve("execution.json")));
            try (var files = Files.list(work)) {
                assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("worker-ready-")
                    || file.getFileName().toString().endsWith(".log")));
            }
            try (var store = new ArchiveStore(archive)) {
                var saved = store.admissionObservation(ledger.id);
                assertEquals("UNKNOWN", saved.get("admission"));
                assertEquals("UNKNOWN", saved.get("admission_outcome"));
                assertEquals("ADMISSION_UNRESOLVED", saved.get("state"));
                assertFalse(saved.containsKey("worker_pid"));
            }
            var ownership = WorkerOwnership.read(archive.resolve("crawl-worker.lock"));
            assertEquals("STOPPED", ownership.get("phase"));
            assertEquals(false, ownership.get("child_created"));
            assertEquals(true, ownership.get("cleanup_confirmed"));
        }
    }
}
