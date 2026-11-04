// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crawlercommons.urlfrontier.Urlfrontier.*;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import io.grpc.stub.StreamObserver;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real RocksDB, three differently populated queues, clean and process-crash reopen.
 * "Completed" below means unscheduled storage rows, never successful crawl outcome.
 * Direct owning-service writes are fixtures, not proof of RPC authentication.
 */
public final class RecoveryCountersConformance {
    private static String base;
    private static final String[] IDS = {"alpha", "middle", "zeta"};
    private static final int[] RESERVED = {3, 2, 3};
    private static final int[] COMPLETED = {1, 2, 0};

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static RocksDBService service(Path path) {
        // Deliberately no rocksdb.recovery.check setting: candidate must rebuild anyway.
        return new RocksDBService(Map.of("rocksdb.path", path.toString()), "localhost", 7071);
    }
    private static JsonObject command(String action, String id) {
        return AdmissionConformance.command(action, id);
    }
    private static JsonObject reserve(RocksDBService s, String id, int index) {
        return s.admissionCommand(AdmissionConformance.reserve(id,
            index == 0 ? "/seed" : "/child" + index, index == 0 ? "" : "/seed", index == 0 ? 0 : 1));
    }
    private static URLInfo info(String id, int index) {
        return URLInfo.newBuilder().setCrawlID(id).setKey(java.net.URI.create(base).getHost())
            .setUrl(base + (index == 0 ? "/seed" : "/child" + index)).build();
    }
    private static void update(RocksDBService s, String id, int index, long due) {
        AckMessage.Status status = s.putURLItem(URLItem.newBuilder().setKnown(KnownURLItem.newBuilder()
            .setInfo(info(id, index)).setRefetchableFromDate(due)).build());
        check(status == AckMessage.Status.OK, "Fixture scheduling update rejected");
    }
    private static void populate(RocksDBService s) {
        for (int q = 0; q < IDS.length; q++) {
            s.admissionCommand(AdmissionConformance.open(IDS[q], 4, 1));
            for (int i = 0; i < RESERVED[q]; i++) {
                check(reserve(s, IDS[q], i).get("outcome").getAsString().equals("RESERVED"), "Initial admission");
            }
            for (int i = 0; i < COMPLETED[q]; i++) update(s, IDS[q], i, 0);
        }
        update(s, "alpha", 2, 4102444800L); // Far-future retry; never shorten production policy.
    }
    private static List<URLInfo> due(RocksDBService s, String id) {
        List<URLInfo> result = new ArrayList<>();
        s.getURLs(GetParams.newBuilder().setCrawlID(id).setMaxQueues(10)
            .setMaxUrlsPerQueue(10).setDelayRequestable(120).build(), new StreamObserver<URLInfo>() {
                public void onNext(URLInfo item) { result.add(item); }
                public void onError(Throwable error) { throw new AssertionError(error); }
                public void onCompleted() { }
            });
        return result;
    }
    private static JsonObject inspect(RocksDBService s) {
        check(s.getQueues().size() == 3, "Missing or spurious queue");
        JsonObject snapshot = new JsonObject();
        for (int q = 0; q < IDS.length; q++) {
            String id = IDS[q];
            var queue = s.getQueues().get(QueueWithinCrawl.get(java.net.URI.create(base).getHost(), id));
            check(queue != null, "Missing queue " + id);
            check(queue.countActive() == RESERVED[q] - COMPLETED[q], "Wrong active " + id);
            check(queue.getCountCompleted() == COMPLETED[q], "Wrong completed " + id);
            JsonObject session = s.admissionCommand(command("session", id));
            check(session.get("reserved_count").getAsInt() == RESERVED[q], "Quota changed " + id);
            JsonObject rows = new JsonObject();
            for (int i = 0; i < RESERVED[q]; i++) {
                JsonObject c = command("reservation", id); c.addProperty("url", info(id, i).getUrl());
                rows.add(Integer.toString(i), s.admissionCommand(c));
                final List<URLItem> state = new ArrayList<>();
                s.getURLStatus(URLStatusRequest.newBuilder().setCrawlID(id)
                    .setKey(java.net.URI.create(base).getHost()).setUrl(info(id, i).getUrl()).build(),
                    new StreamObserver<URLItem>() {
                        public void onNext(URLItem item) { state.add(item); }
                        public void onError(Throwable error) { throw new AssertionError(error); }
                        public void onCompleted() { }
                    });
                check(state.size() == 1 && state.get(0).hasKnown(), "Missing persisted URL state");
                rows.addProperty("due" + i, state.get(0).getKnown().getRefetchableFromDate());
            }
            rows.add("session", session); snapshot.add(id, rows);
        }
        return snapshot;
    }
    private static void verifyDueAndReplay(RocksDBService s) {
        JsonObject before = inspect(s);
        for (int q = 0; q < IDS.length; q++) {
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < RESERVED[q]; i++) {
                if (i >= COMPLETED[q] && !(q == 0 && i == 2)) expected.add(info(IDS[q], i).getUrl());
                check(reserve(s, IDS[q], i).get("outcome").getAsString().equals("ALREADY_RESERVED"), "Replay enqueued work");
            }
            List<URLInfo> leased = due(s, IDS[q]);
            Set<String> actual = new HashSet<>();
            for (URLInfo row : leased) actual.add(row.getUrl());
            check(actual.equals(expected) && leased.size() == expected.size(), "Due membership " + IDS[q]);
        }
        check(before.equals(inspect(s)), "Reads/replay/leases changed quota or reservation identity");
    }
    public static void main(String[] args) throws Exception {
        base = "http://" + AdmissionConformance.host();
        AdmissionConformance.base = base;
        if (args.length == 2 && args[0].equals("crash")) {
            RocksDBService s = service(Path.of(args[1]));
            populate(s);
            Files.writeString(Path.of(args[1]).resolveSibling("crash-expected.json"), inspect(s).toString());
            // Deliberately bypass close/shutdown hooks, without deleting any database.
            Runtime.getRuntime().halt(23);
        }
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Path clean = root.resolve("clean");
        JsonObject original;
        try (RocksDBService s = service(clean)) { populate(s); original = inspect(s); }
        for (int restart = 1; restart <= 2; restart++) {
            try (RocksDBService s = service(clean)) {
                check(original.equals(inspect(s)), "Clean restart changed reservations " + restart);
                verifyDueAndReplay(s);
            }
        }
        Path crash = root.resolve("process-crash");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), RecoveryCountersConformance.class.getName(),
            "crash", crash.toString()).redirectErrorStream(true).redirectOutput(root.resolve("crash-child.log").toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Owned crash fixture timed out");
        }
        check(child.exitValue() == 23, "Crash fixture did not reach deliberate halt");
        JsonObject crashExpected = JsonParser.parseString(Files.readString(root.resolve("crash-expected.json"))).getAsJsonObject();
        try (RocksDBService s = service(crash)) {
            check(crashExpected.equals(inspect(s)), "Crash recovery changed due dates or reservations");
            verifyDueAndReplay(s);
        }
        System.out.println("PASS RecoveryCountersConformance: three queues, two clean restarts, process crash, due membership and quota replay");
    }
}
