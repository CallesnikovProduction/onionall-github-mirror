// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.*;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real RocksDB plus loopback RPC: failed transport is not an admission rollback. */
public final class AdmissionAmbiguousRpcConformance {
    private static final String TOKEN = "synthetic-ambiguous-rpc-capability-00000000";
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "Fault synchronization timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private enum Mode { LOST_ACK, BEFORE_COMMIT, AFTER_COMMIT }
    private static final class Fault {
        final String action, session;
        final Mode mode;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
            committed = new CountDownLatch(1), completed = new CountDownLatch(1);
        Fault(String action, String session, Mode mode) {
            this.action = action; this.session = session; this.mode = mode;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final RocksDBService service;
        final Server server;
        final ManagedChannel raw;
        final Channel client;
        final AtomicReference<Fault> fault = new AtomicReference<>();
        Fixture(Path database) throws Exception {
            service = AdmissionConformance.service(database);
            var definition = ServerServiceDefinition.builder("onionall.frontier.admission.v1.Admission")
                .addMethod(AdmissionRpc.COMMAND, ServerCalls.asyncUnaryCall((request, observer) -> {
                    Fault selected = null;
                    try {
                        JsonObject command = JsonParser.parseString(request.getValue().toStringUtf8()).getAsJsonObject();
                        Fault candidate = fault.get();
                        if (candidate != null && candidate.action.equals(command.get("action").getAsString())
                            && candidate.session.equals(command.get("session_id").getAsString())
                            && fault.compareAndSet(candidate, null)) selected = candidate;
                        if (selected != null) {
                            selected.entered.countDown();
                            if (selected.mode == Mode.BEFORE_COMMIT) await(selected.release);
                        }
                        // Deliberately no cancellation shortcut: the production ledger is not
                        // rolled back by a gRPC deadline either. A late commit must remain possible.
                        JsonObject value = service.admissionCommand(command);
                        if (selected != null) {
                            selected.committed.countDown();
                            if (selected.mode == Mode.AFTER_COMMIT) await(selected.release);
                            if (selected.mode == Mode.LOST_ACK) {
                                observer.onError(Status.UNAVAILABLE.withDescription("Injected lost acknowledgement").asRuntimeException());
                                return;
                            }
                        }
                        if (!Context.current().isCancelled()) {
                            observer.onNext(BytesValue.newBuilder().setValue(ByteString.copyFromUtf8(value.toString())).build());
                            observer.onCompleted();
                        }
                    } catch (IllegalArgumentException | JsonParseException e) {
                        if (!Context.current().isCancelled()) observer.onError(Status.INVALID_ARGUMENT
                            .withDescription("Admission request rejected").asRuntimeException());
                    } catch (RuntimeException | AssertionError e) {
                        if (!Context.current().isCancelled()) observer.onError(Status.INTERNAL.withCause(e).asRuntimeException());
                    } finally {
                        if (selected != null) selected.completed.countDown();
                    }
                })).build();
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(definition, AdmissionRpc.guard(TOKEN))).build().start();
            raw = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER), TOKEN);
            client = ClientInterceptors.intercept(raw, MetadataUtils.newAttachHeadersInterceptor(metadata));
        }
        JsonObject command(JsonObject request) { return command(request, 5); }
        JsonObject command(JsonObject request, int seconds) {
            BytesValue response = ClientCalls.blockingUnaryCall(client, AdmissionRpc.COMMAND,
                CallOptions.DEFAULT.withDeadlineAfter(seconds, TimeUnit.SECONDS), BytesValue.newBuilder()
                    .setValue(ByteString.copyFromUtf8(request.toString())).build());
            return JsonParser.parseString(response.getValue().toStringUtf8()).getAsJsonObject();
        }
        void lost(JsonObject request) {
            Fault injection = new Fault(request.get("action").getAsString(), request.get("session_id").getAsString(), Mode.LOST_ACK);
            fault.set(injection);
            try { command(request); throw new AssertionError("Lost response was reported successful"); }
            catch (StatusRuntimeException e) {
                check(e.getStatus().getCode() == Status.Code.UNAVAILABLE, "Unexpected injected transport status");
            }
            await(injection.committed); await(injection.completed);
        }
        public void close() throws Exception {
            raw.shutdownNow(); server.shutdownNow();
            try {
                check(raw.awaitTermination(5, TimeUnit.SECONDS), "Channel cleanup timed out");
                check(server.awaitTermination(5, TimeUnit.SECONDS), "Server cleanup timed out");
            } finally { service.close(); }
        }
    }
    private static JsonObject request(String action, String session) {
        return AdmissionConformance.command(action, session);
    }
    private static JsonObject reservation(String session, String path) {
        JsonObject command = request("reservation", session);
        command.addProperty("url", AdmissionConformance.base + path); return command;
    }
    private static void outcome(JsonObject value, String expected) {
        check(expected.equals(value.get("outcome").getAsString()), "Unexpected authoritative outcome: " + value);
    }
    private static int count(Fixture f, String session) {
        return f.command(request("session", session)).get("reserved_count").getAsInt();
    }
    private static void lostResponsesAndPartialAdmission(Fixture f, Map<String, String> identities) {
        try {
            f.command(AdmissionConformance.open("unsupported-scale", 101, 1));
            throw new AssertionError("V1 session above100 accepted");
        } catch (StatusRuntimeException e) {
            check(e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT, "Wrong unsupported-scale rejection");
        }
        outcome(f.command(request("session", "unsupported-scale")), "SESSION_NOT_FOUND");
        String id = "lost";
        f.lost(AdmissionConformance.open(id, 3, 1));
        JsonObject session = f.command(request("session", id)); outcome(session, "SESSION_OPEN");
        check(session.get("reserved_count").getAsInt() == 0, "Open unexpectedly reserved work");
        check(f.command(AdmissionConformance.open(id, 3, 1)).equals(session), "Identical open changed immutable session");
        outcome(f.command(AdmissionConformance.open(id, 2, 1)), "SESSION_CONFLICT");
        check(count(f, id) == 0, "Conflicting open mutated quota");
        f.lost(AdmissionConformance.reserve(id, "/seed", "", 0));
        JsonObject committed = f.command(reservation(id, "/seed")); outcome(committed, "RESERVED");
        identities.put(id, committed.get("reservation_id").getAsString());
        check(count(f, id) == 1, "Lost reserve response lost its commit");
        f.lost(request("seal", id));
        check(f.command(request("session", id)).get("sealed").getAsBoolean(), "Lost seal response lost its commit");
        JsonObject replay = f.command(AdmissionConformance.reserve(id, "/seed", "", 0));
        outcome(replay, "ALREADY_RESERVED");
        check(replay.get("reservation_id").equals(committed.get("reservation_id")), "Replay changed reservation identity");
        check(count(f, id) == 1, "Replay spent quota");
        check(AdmissionConformance.dequeue(f.service, id).size() == 1, "Committed reservation not enqueued exactly once");
        URLInfo info = URLInfo.newBuilder().setCrawlID(id).setKey(java.net.URI.create(AdmissionConformance.base).getHost())
            .setUrl(AdmissionConformance.base + "/seed").build();
        check(f.service.putURLItem(URLItem.newBuilder().setKnown(KnownURLItem.newBuilder().setInfo(info)
            .setRefetchableFromDate(0)).build()) == AckMessage.Status.OK, "Cannot retain terminal scheduler state");

        String partial = "partial";
        JsonObject open = AdmissionConformance.open(partial, 3, 1);
        open.getAsJsonArray("seeds").add(AdmissionConformance.base + "/second");
        open.getAsJsonArray("seeds").add(AdmissionConformance.base + "/third");
        f.command(open); f.command(AdmissionConformance.reserve(partial, "/seed", "", 0));
        f.lost(AdmissionConformance.reserve(partial, "/second", "", 0));
        check(count(f, partial) == 2, "Partial committed admission was rolled back");
        f.command(open);
        for (String path : List.of("/seed", "/second")) {
            JsonObject before = f.command(reservation(partial, path));
            JsonObject duplicate = f.command(AdmissionConformance.reserve(partial, path, "", 0));
            outcome(duplicate, "ALREADY_RESERVED");
            check(before.get("reservation_id").equals(duplicate.get("reservation_id")), "Partial replay changed identity");
        }
        check(count(f, partial) == 2, "Partial replay spent additional quota");
        f.command(AdmissionConformance.reserve(partial, "/third", "", 0));
        check(count(f, partial) == 3, "Partial admission could not continue");
    }
    private static void deadline(Fixture f, boolean beforeCommit, Map<String, String> identities) throws Exception {
        String id = beforeCommit ? "before-deadline" : "after-deadline";
        f.command(AdmissionConformance.open(id, 2, 1));
        Fault injection = new Fault("reserve", id, beforeCommit ? Mode.BEFORE_COMMIT : Mode.AFTER_COMMIT);
        f.fault.set(injection);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Status.Code> pending = executor.submit(() -> {
            try { f.command(AdmissionConformance.reserve(id, "/seed", "", 0), 2); return Status.Code.OK; }
            catch (StatusRuntimeException e) { return e.getStatus().getCode(); }
        });
        try {
            await(injection.entered);
            if (!beforeCommit) await(injection.committed);
            check(pending.get(8, TimeUnit.SECONDS) == Status.Code.DEADLINE_EXCEEDED, "Deadline fixture did not expire");
            JsonObject readback = f.command(reservation(id, "/seed"));
            outcome(readback, beforeCommit ? "RESERVATION_NOT_FOUND" : "RESERVED");
            check(count(f, id) == (beforeCommit ? 0 : 1), "Unexpected state at expired deadline");
            injection.release.countDown(); await(injection.committed); await(injection.completed);
            JsonObject finalReadback = f.command(reservation(id, "/seed")); outcome(finalReadback, "RESERVED");
            identities.put(id, finalReadback.get("reservation_id").getAsString());
            check(count(f, id) == 1, "Late commit not visible through authoritative readback");
            // No client retry was issued. In BEFORE_COMMIT the negative readback was
            // followed by the original commit, proving it was not a rejection barrier.
        } finally {
            injection.release.countDown(); executor.shutdownNow();
            check(executor.awaitTermination(5, TimeUnit.SECONDS), "Deadline client did not terminate");
            await(injection.completed);
        }
    }
    private static void reopened(Fixture f, Map<String, String> identities) {
        for (var entry : identities.entrySet()) {
            String id = entry.getKey();
            JsonObject stored = f.command(reservation(id, "/seed")); outcome(stored, "RESERVED");
            check(entry.getValue().equals(stored.get("reservation_id").getAsString()), "Restart changed reservation identity");
            check(count(f, id) == 1, "Restart changed quota");
            JsonObject replay = f.command(AdmissionConformance.reserve(id, "/seed", "", 0));
            outcome(replay, "ALREADY_RESERVED"); check(count(f, id) == 1, "Restart replay spent quota");
            int expected = id.equals("lost") ? 0 : 1;
            check(AdmissionConformance.dequeue(f.service, id).size() == expected, "Restart replay re-enqueued or lost work");
        }
        check(f.command(request("session", "lost")).get("sealed").getAsBoolean(), "Seal was not durable");
        check(count(f, "partial") == 3, "Partial admission was not durable");
        check(AdmissionConformance.dequeue(f.service, "partial").size() == 3, "Partial replay duplicated or lost enqueued rows");
    }
    public static void main(String[] args) throws Exception {
        AdmissionConformance.base = "http://" + AdmissionConformance.host();
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Map<String, String> identities = new LinkedHashMap<>();
        try (Fixture f = new Fixture(root.resolve("db"))) {
            lostResponsesAndPartialAdmission(f, identities);
            deadline(f, true, identities); deadline(f, false, identities);
        }
        try (Fixture f = new Fixture(root.resolve("db"))) { reopened(f, identities); }
        System.out.println("ADMISSION_AMBIGUOUS_RPC_PASS lost-open/reserve/seal deadline-before/after late-commit-after-negative-readback partial-replay conflict restart stable-id quota enqueue");
    }
}
