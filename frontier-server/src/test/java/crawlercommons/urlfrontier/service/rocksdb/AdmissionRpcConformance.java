// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import com.google.gson.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.*;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
/** Real guarded gRPC/RocksDB checks, including a separate-process restart. */
public final class AdmissionRpcConformance {
    private static final String ID = "rpc-protected";
    private static final Metadata.Key<String> TOKEN = Metadata.Key.of(
        "x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER);

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void denied(Runnable action) {
        try { action.run(); throw new AssertionError("Missing PERMISSION_DENIED"); }
        catch (StatusRuntimeException e) {
            check(e.getStatus().getCode() == Status.Code.PERMISSION_DENIED,
                "Unexpected rejection: " + e.getStatus().getCode());
        }
    }
    private static Channel withToken(Channel channel, String token) {
        Metadata headers = new Metadata(); headers.put(TOKEN, token);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    private static JsonObject command(Channel channel, JsonObject request) {
        BytesValue reply = ClientCalls.blockingUnaryCall(channel, AdmissionRpc.COMMAND,
            CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
            BytesValue.newBuilder().setValue(ByteString.copyFromUtf8(request.toString())).build());
        return JsonParser.parseString(reply.getValue().toStringUtf8()).getAsJsonObject();
    }
    private static URLInfo info(String id, String url) {
        return URLInfo.newBuilder().setCrawlID(id).setKey(java.net.URI.create(url).getHost()).setUrl(url).build();
    }
    private static URLItem known(String url, long date, boolean forged) {
        URLInfo.Builder metadata = info(ID, url).toBuilder();
        if (forged) metadata.putMetadata("discovery.source", StringList.newBuilder().addValues("synthetic-forgery").build());
        return URLItem.newBuilder().setKnown(KnownURLItem.newBuilder().setInfo(metadata).setRefetchableFromDate(date)).build();
    }
    private static URLItem discovered(String id, String url) {
        return URLItem.newBuilder().setDiscovered(DiscoveredURLItem.newBuilder().setInfo(info(id, url))).build();
    }
    private static final class WriteResult {
        final List<AckMessage> acks = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
    }
    private static WriteResult put(Channel channel, URLItem... items) {
        WriteResult result = new WriteResult(); CountDownLatch done = new CountDownLatch(1);
        StreamObserver<URLItem> requests = URLFrontierGrpc.newStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .putURLs(new StreamObserver<AckMessage>() {
                public void onNext(AckMessage ack) { result.acks.add(ack); }
                public void onError(Throwable error) { result.failure.set(error); done.countDown(); }
                public void onCompleted() { done.countDown(); }
            });
        for (URLItem item : items) requests.onNext(item);
        requests.onCompleted();
        try { check(done.await(8, TimeUnit.SECONDS), "PutURLs timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        return result;
    }
    private static void deniedPut(Channel channel, URLItem... items) {
        WriteResult result = put(channel, items);
        check(result.acks.isEmpty(), "Rejected stream acknowledged work");
        check(result.failure.get() != null && Status.fromThrowable(result.failure.get()).getCode()
            == Status.Code.PERMISSION_DENIED, "PutURLs was not denied before dispatch");
    }
    private static void acceptedPut(Channel channel, URLItem item, AckMessage.Status expected) {
        WriteResult result = put(channel, item);
        check(result.failure.get() == null && result.acks.size() == 1 && result.acks.get(0).getStatus() == expected,
            "Unexpected authorized PutURLs result");
    }
    private static URLItem state(Channel channel, String url) {
        return URLFrontierGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).getURLStatus(
            URLStatusRequest.newBuilder().setCrawlID(ID).setKey(java.net.URI.create(url).getHost()).setUrl(url).build());
    }
    private static List<URLInfo> lease(Channel channel, String crawl) {
        Iterator<URLInfo> rows = URLFrontierGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .getURLs(GetParams.newBuilder().setCrawlID(crawl).setMaxQueues(1).setMaxUrlsPerQueue(10).setDelayRequestable(120).build());
        List<URLInfo> result = new ArrayList<>(); rows.forEachRemaining(result::add); return result;
    }
    private static JsonObject reservationRequest(String url) {
        JsonObject request = AdmissionConformance.command("reservation", ID); request.addProperty("url", url); return request;
    }
    private static final class Fixture implements AutoCloseable {
        final RocksDBService service; final Server server; final ManagedChannel raw;
        final Channel authorized, wrong; final String token;
        Fixture(Path database) throws Exception {
            service = AdmissionConformance.service(database);
            token = UUID.randomUUID().toString(); ServerInterceptor guard = AdmissionRpc.guard(token);
            server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1",0)).addService(ServerInterceptors.intercept(service, guard))
                .addService(ServerInterceptors.intercept(AdmissionRpc.bind(service), guard)).maxInboundMessageSize(131072).build().start();
            raw = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            authorized = withToken(raw, token); wrong = withToken(raw, UUID.randomUUID().toString());
        }
        public void close() {
            raw.shutdownNow(); server.shutdownNow();
            try {
                check(raw.awaitTermination(5, TimeUnit.SECONDS), "Client channel did not stop");
                check(server.awaitTermination(5, TimeUnit.SECONDS), "Fixture server did not stop");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new AssertionError("Fixture close interrupted", e);
            } finally {
                try { service.close(); }
                catch (java.io.IOException e) { throw new AssertionError("Fixture storage close failed", e); }
            }
        }
    }
    private static void adminDenied(Channel channel) {
        Set<String> admins = Set.of("DeleteCrawl", "DeleteQueue", "SetActive", "SetDelay", "BlockQueueUntil", "SetLogLevel"); int checked = 0;
        for (MethodDescriptor<?, ?> method : URLFrontierGrpc.getServiceDescriptor().getMethods()) {
            if (!admins.contains(method.getBareMethodName())) continue;
            MethodDescriptor<Empty, Empty> call = MethodDescriptor.<Empty, Empty>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName(method.getFullMethodName())
                .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())).build();
            denied(() -> ClientCalls.blockingUnaryCall(channel, call, CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), Empty.getDefaultInstance())); checked++;
        }
        check(checked == 6, "Incomplete administrative mutation matrix");
    }
    private static void verifyUnchanged(Fixture f, String seed, URLItem before, JsonObject reservation) {
        check(state(f.raw, seed).equals(before), "Scheduling/metadata changed after rejection");
        verifyAdmission(f, seed, reservation);
        check(!URLFrontierGrpc.newBlockingStub(f.raw).withDeadlineAfter(3, TimeUnit.SECONDS)
            .listURLs(ListUrlParams.newBuilder().setCrawlID("unprotected").setSize(10).build()).hasNext(),
            "Rejected unprotected write created a queue row");
    }
    private static void verifyAdmission(Fixture f, String seed, JsonObject reservation) {
        check(command(f.authorized, reservationRequest(seed)).equals(reservation), "Immutable reservation changed");
        check(command(f.authorized, AdmissionConformance.command("session", ID)).get("reserved_count").getAsInt() == 1,
            "Quota changed after rejection/replay");
    }
    private static void rejectMutations(Fixture f, String seed) {
        for (Channel channel : List.of(f.raw, f.wrong)) {
            denied(() -> command(channel, AdmissionConformance.command("session", ID)));
            deniedPut(channel, known(seed, 0, true), known(seed, Instant.now().getEpochSecond() + 3600, true));
            deniedPut(channel, discovered("unprotected", AdmissionConformance.base + "/unapproved"));
            denied(() -> lease(channel, ID)); denied(() -> lease(channel, "unprotected"));
        }
    }
    private static void initial(Path root) throws Exception {
        String seed = AdmissionConformance.base + "/seed";
        try (Fixture f = new Fixture(root.resolve("db"))) {
            denied(() -> command(f.raw, AdmissionConformance.open(ID, 3, 1)));
            command(f.authorized, AdmissionConformance.open(ID, 3, 1)); command(f.authorized, AdmissionConformance.reserve(ID, "/seed", "", 0));
            URLItem before = state(f.raw, seed); JsonObject reservation = command(f.authorized, reservationRequest(seed));
            rejectMutations(f, seed); verifyUnchanged(f, seed, before, reservation);
            JsonObject expected = new JsonObject(); expected.addProperty("state", Base64.getEncoder().encodeToString(before.toByteArray())); expected.add("reservation", reservation);
            Files.writeString(root.resolve("expected.json"), expected.toString());
            adminDenied(f.raw); adminDenied(f.authorized);
            URLFrontierGrpc.newBlockingStub(f.raw).withDeadlineAfter(3, TimeUnit.SECONDS).getActive(Local.getDefaultInstance());
        }
    }
    private static void reopened(Path root) throws Exception {
        String seed = AdmissionConformance.base + "/seed";
        JsonObject expected = JsonParser.parseString(Files.readString(root.resolve("expected.json"))).getAsJsonObject();
        URLItem before = URLItem.parseFrom(Base64.getDecoder().decode(expected.get("state").getAsString())); JsonObject reservation = expected.getAsJsonObject("reservation");
        try (Fixture f = new Fixture(root.resolve("db"))) {
            verifyUnchanged(f, seed, before, reservation); rejectMutations(f, seed); verifyUnchanged(f, seed, before, reservation);
            List<URLInfo> leased = lease(f.authorized, ID);
            check(leased.size() == 1 && leased.get(0).getUrl().equals(seed), "Rejected GetURLs consumed legitimate lease");
            check(lease(f.authorized, ID).isEmpty(), "Valid lease was not recorded");
            long retry = Instant.now().getEpochSecond() + 3600;
            acceptedPut(f.authorized, known(seed, retry, false), AckMessage.Status.OK);
            check(state(f.raw, seed).getKnown().getRefetchableFromDate() == retry, "Authenticated retry/success date not stored");
            acceptedPut(f.authorized, known(seed, 0, false), AckMessage.Status.OK);
            check(state(f.raw, seed).getKnown().getRefetchableFromDate() == 0, "Authenticated terminal update failed");
            URLItem terminal = state(f.raw, seed);
            check(command(f.authorized, AdmissionConformance.reserve(ID, "/seed", "", 0)).get("outcome").getAsString().equals("ALREADY_RESERVED"), "Replay allocated again");
            verifyUnchanged(f, seed, terminal, reservation); check(lease(f.authorized, ID).isEmpty(), "Replay re-enqueued terminal URL");
            acceptedPut(f.authorized, known(AdmissionConformance.base + "/unreserved", retry, false), AckMessage.Status.FAIL);
            acceptedPut(f.authorized, discovered(ID, AdmissionConformance.base + "/unreserved"), AckMessage.Status.FAIL);
            ManagedChannel recreated = ManagedChannelBuilder.forAddress("127.0.0.1", f.server.getPort()).usePlaintext().build();
            try {
                Channel authorized = withToken(recreated, f.token);
                long rescheduled = Instant.now().getEpochSecond();
                acceptedPut(authorized, known(seed, rescheduled, false), AckMessage.Status.OK);
                // The existing queue-wide request delay still applies independently of
                // this URL's date; rescheduling must not silently bypass that scheduler.
                check(state(f.raw, seed).getKnown().getRefetchableFromDate() == rescheduled,
                    "Authenticated rescheduling on recreated channel failed");
                acceptedPut(authorized, known(seed, 0, false), AckMessage.Status.OK);
            } finally { recreated.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
        }
        try (Fixture f = new Fixture(root.resolve("db"))) {
            check(state(f.raw, seed).getKnown().getRefetchableFromDate() == 0, "Authorized terminal state not durable");
            verifyAdmission(f, seed, reservation);
        }
    }
    public static void main(String[] args) throws Exception {
        AdmissionConformance.base = "http://" + AdmissionConformance.host(); Path root = Path.of(args[0]);
        if (args.length == 2 && args[1].equals("reopen")) { reopened(root); System.out.println("ADMISSION_RPC_REOPEN_PASS rejected-state-persisted lease-preserved authorized-retry-terminal replay-quota"); return; }
        Files.createDirectories(root); initial(root);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), AdmissionRpcConformance.class.getName(), root.toString(), "reopen").inheritIO().start();
        try { check(child.waitFor(30, TimeUnit.SECONDS), "Restart conformance timed out"); check(child.exitValue() == 0, "Restart conformance failed"); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        System.out.println("ADMISSION_RPC_CONFORMANCE_PASS missing-wrong-token all-crawl-writes leases no-mutation process-restart admin-denials valid-updates replay");
    }
}
