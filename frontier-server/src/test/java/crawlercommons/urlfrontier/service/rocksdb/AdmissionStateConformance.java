// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.*;
import com.google.protobuf.*;
import crawlercommons.urlfrontier.Urlfrontier.*;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import io.grpc.*;
import io.grpc.stub.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.rocksdb.RocksDB;

/** Actual RocksDB/RPC readback, restart/crash and non-mutation proofs. No crawler/network fixture. */
public final class AdmissionStateConformance {
    private static String base;
    private static final String ID = "state";
    private static final String TOKEN = "synthetic-state-test-capability-0000000000";
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static RocksDBService service(Path p) { return AdmissionConformance.service(p); }
    private static JsonObject request(String action) { return AdmissionConformance.command(action, ID); }
    private static JsonObject read(RocksDBService s, String action) { return s.admissionCommand(request(action)); }
    private static JsonObject page(RocksDBService s, String cursor) {
        JsonObject c = request("list"); c.addProperty("limit", 2);
        if (cursor != null) c.addProperty("cursor", cursor);
        return s.admissionCommand(c);
    }
    private static void reserve(RocksDBService s, int i) {
        JsonObject c = AdmissionConformance.reserve(ID, i == 0 ? "/seed" : "/child" + i, i == 0 ? "" : "/seed", i == 0 ? 0 : 1);
        check(s.admissionCommand(c).get("outcome").getAsString().equals("RESERVED"), "Reservation failed");
    }
    private static QueueMetadata queue(RocksDBService s) {
        return (QueueMetadata) s.getQueues().get(QueueWithinCrawl.get(java.net.URI.create(base).getHost(), ID));
    }
    private static void update(RocksDBService s, int i, long date) {
        URLInfo info = URLInfo.newBuilder().setCrawlID(ID).setKey(java.net.URI.create(base).getHost())
            .setUrl(base + (i == 0 ? "/seed" : "/child" + i)).build();
        check(s.putURLItem(URLItem.newBuilder().setKnown(KnownURLItem.newBuilder().setInfo(info)
            .setRefetchableFromDate(date)).build()) == AckMessage.Status.OK, "Update failed");
    }
    private static void populate(RocksDBService s) {
        s.admissionCommand(AdmissionConformance.open(ID, 10, 1));
        for (int i = 0; i < 5; i++) reserve(s, i);
        update(s, 0, 0); update(s, 1, 0); update(s, 4, 4102444800L);
        queue(s).holdUntil(base + "/child3", Instant.now().getEpochSecond() + 3600);
        queue(s).holdUntil("synthetic-expired-lease", 1);
    }
    private static RocksDB db(RocksDBService s) throws Exception {
        var f = RocksDBService.class.getDeclaredField("rocksDB"); f.setAccessible(true); return (RocksDB) f.get(s);
    }
    private static Object rawLeases(QueueMetadata q) throws Exception {
        var f = QueueMetadata.class.getDeclaredField("beingProcessed"); f.setAccessible(true);
        synchronized(q) { return new LinkedHashMap<>((Map<?,?>) f.get(q)); }
    }
    private static void counts(RocksDBService s, int expected, int held) {
        JsonObject c = read(s, "counts").getAsJsonObject("counts");
        check(c.get("reserved").getAsInt() == expected, "Reserved count");
        check(c.get("unscheduled").getAsInt() == 2, "Unscheduled count");
        check(c.get("scheduled_wait").getAsInt() == 1, "Future count");
        check(c.get("held").getAsInt() == held, "Held count");
        check(c.get("pending").getAsInt() == expected - 3 - held, "Pending count");
        check(c.get("unknown").getAsInt() == 0, "Unexpected unknown row");
        for (String key : List.of("done", "success", "terminal", "retry_wait")) check(c.get(key).isJsonNull(), "Invented " + key);
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid cursor/limit accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void rpcGuard(RocksDBService s) throws Exception {
        Server server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(AdmissionRpc.bind(s), AdmissionRpc.guard(TOKEN))).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            for (String offered : List.of("", "wrong", TOKEN)) {
                Metadata headers = new Metadata(); if (!offered.isEmpty()) headers.put(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER), offered);
                Channel client = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
                for (String action : List.of("list", "counts")) {
                    try {
                        BytesValue value = ClientCalls.blockingUnaryCall(client, AdmissionRpc.COMMAND,
                            CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), BytesValue.newBuilder()
                            .setValue(ByteString.copyFromUtf8(request(action).toString())).build());
                        check(offered.equals(TOKEN), "Unauthenticated read accepted");
                        check(JsonParser.parseString(value.getValue().toStringUtf8()).getAsJsonObject().get("outcome").getAsString()
                            .equals(action.equals("list") ? "STATE_LIST" : "STATE_COUNTS"), "Wrong RPC readback");
                    } catch (StatusRuntimeException e) {
                        check(!offered.equals(TOKEN) && e.getStatus().getCode() == Status.Code.PERMISSION_DENIED, "Wrong RPC rejection");
                    }
                }
            }
        } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
    private static void longPages(RocksDBService s) {
        s.admissionCommand(AdmissionConformance.open("large", 40, 1));
        s.admissionCommand(AdmissionConformance.reserve("large", "/seed", "", 0));
        for (int i = 1; i < 35; i++) s.admissionCommand(AdmissionConformance.reserve("large", "/" + "x".repeat(1850) + i, "/seed", 1));
        String cursor = null; Set<Integer> ordinals = new HashSet<>(); int pages = 0;
        do {
            JsonObject c = AdmissionConformance.command("list", "large"); c.addProperty("limit", 200);
            if (cursor != null) c.addProperty("cursor", cursor);
            JsonObject p = s.admissionCommand(c); pages++;
            check(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65536, "Response exceeded client envelope");
            for (JsonElement row : p.getAsJsonArray("rows")) check(ordinals.add(row.getAsJsonObject().get("ordinal").getAsInt()), "Duplicate long row");
            cursor = p.get("next_cursor").isJsonNull() ? null : p.get("next_cursor").getAsString();
        } while (cursor != null);
        check(pages > 1 && ordinals.size() == 35, "Byte-bounded pagination lost rows");
    }
    public static void main(String[] args) throws Exception {
        base = "http://" + AdmissionConformance.host(); AdmissionConformance.base = base;
        if (args.length == 2 && args[0].equals("crash")) {
            RocksDBService s = service(Path.of(args[1])); populate(s); counts(s, 5, 1);
            Runtime.getRuntime().halt(23);
        }
        Path root = Path.of(args[0]); Files.createDirectories(root);
        try (RocksDBService s = service(root.resolve("clean"))) {
            populate(s); counts(s, 5, 1);
            JsonObject originalSession = read(s, "session");
            long before = db(s).getLatestSequenceNumber(); Object leases = rawLeases(queue(s));
            for (int i = 0; i < 5; i++) { page(s, null); counts(s, 5, 1); }
            rpcGuard(s);
            s.admission.readOnly = true;
            try { page(s, null); counts(s, 5, 1); }
            finally { s.admission.readOnly = false; }
            check(before == db(s).getLatestSequenceNumber(), "Read wrote persistent state");
            check(leases.equals(rawLeases(queue(s))), "Read mutated even an expired lease");
            check(originalSession.equals(read(s, "session")), "Read changed quota/session");
            JsonObject first = page(s, null);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try { executor.submit(() -> reserve(s, 5)).get(5, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
            List<Integer> ordinals = new ArrayList<>(); JsonObject p = first;
            do {
                check(p.get("through_ordinal").getAsInt() == 5, "Cursor watermark moved");
                for (JsonElement row : p.getAsJsonArray("rows")) ordinals.add(row.getAsJsonObject().get("ordinal").getAsInt());
                p = p.get("next_cursor").isJsonNull() ? null : page(s, p.get("next_cursor").getAsString());
            } while (p != null);
            check(ordinals.equals(List.of(1,2,3,4,5)), "Pagination skipped/duplicated rows during append");
            counts(s, 6, 1);
            for (String cursor : List.of("!", Base64.getUrlEncoder().encodeToString("1:other:5:2".getBytes()),
                Base64.getUrlEncoder().encodeToString("1:state:9:2".getBytes()))) invalid(() -> page(s, cursor));
            JsonObject bad = request("list"); bad.addProperty("limit", 201); invalid(() -> s.admissionCommand(bad));
            longPages(s);
        }
        for (int i = 0; i < 2; i++) try (RocksDBService s = service(root.resolve("clean"))) { counts(s, 6, 0); }
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), AdmissionStateConformance.class.getName(),
            "crash", root.resolve("crash").toString()).redirectErrorStream(true).redirectOutput(root.resolve("crash-child.log").toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); throw new AssertionError("Crash fixture timeout"); }
        check(child.exitValue() == 23, "Missing deliberate crash");
        try (RocksDBService s = service(root.resolve("crash"))) { counts(s, 5, 0); }
        System.out.println("PASS AdmissionStateConformance: read-only/auth/pagination/append/restart/crash/UNKNOWN");
    }
}
