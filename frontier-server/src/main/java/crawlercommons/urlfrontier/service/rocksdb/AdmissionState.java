// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;

import com.google.gson.*;
import crawlercommons.urlfrontier.service.QueueWithinCrawl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.rocksdb.*;

/** Bounded observation of the existing admission/scheduling rows, no new index/schema.
 * Caller holds admissionLock; a session has one immutable origin/queue. Its monitor
 * additionally freezes lease publication for the duration of this observation.
 */
final class AdmissionState {
    private final RocksDBService owner;
    private final RocksDB db;
    private final ColumnFamilyHandle ledger;
    AdmissionState(RocksDBService owner, RocksDB db, ColumnFamilyHandle ledger) {
        this.owner = owner; this.db = db; this.ledger = ledger;
    }
    JsonObject read(JsonObject request, JsonObject session) throws RocksDBException {
        String id = AdmissionLedger.text(request, "session_id", 64);
        String host = java.net.URI.create(session.getAsJsonObject("spec").get("origin").getAsString()).getHost();
        Object queue = owner.getQueues().get(QueueWithinCrawl.get(host, id));
        synchronized (queue == null ? owner.admissionLock : queue) {
            return observe(request, session, queue instanceof QueueMetadata ? (QueueMetadata) queue : null);
        }
    }
    private JsonObject observe(JsonObject request, JsonObject session, QueueMetadata queue) throws RocksDBException {
        String id = AdmissionLedger.text(request, "session_id", 64);
        int reserved = session.get("reserved_count").getAsInt();
        if (reserved < 0 || reserved > 100) throw new IllegalStateException("Invalid reservation bound");
        if (reserved != 0 && queue == null) throw new IllegalStateException("Session queue unavailable");
        long now = Instant.now().getEpochSecond();
        Map<String, Long> held = queue == null ? Collections.emptyMap() : queue.leaseExpiriesAt(now);
        List<JsonObject> rows = new ArrayList<>();
        byte[] prefix = AdmissionLedger.key("reservation", id);
        try (RocksIterator it = db.newIterator(ledger)) {
            for (it.seek(prefix); it.isValid(); it.next()) {
                byte[] key = it.key();
                if (key.length < prefix.length || !Arrays.equals(prefix, Arrays.copyOf(key, prefix.length))) break;
                if (rows.size() >= 100) throw new IllegalStateException("Reservation limit exceeded");
                JsonObject row = JsonParser.parseString(new String(it.value(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!id.equals(row.get("session_id").getAsString())) throw new IllegalStateException("Session mismatch");
                // Historical enqueue acknowledgement isn't current scheduling state.
                row.remove("outcome"); row.remove("enqueued");
                row.add("scheduler", owner.schedulingObservation(id, row.get("url").getAsString(), now, held));
                rows.add(row);
            }
            it.status();
        }
        rows.sort(Comparator.comparingInt(row -> row.get("ordinal").getAsInt()));
        if (rows.size() != reserved) throw new IllegalStateException("Incomplete reservation readback");
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).get("ordinal").getAsInt() != i + 1) throw new IllegalStateException("Invalid reservation ordinal");
        JsonObject result = new JsonObject();
        boolean listing = "list".equals(request.get("action").getAsString());
        result.addProperty("outcome", listing ? "STATE_LIST" : "STATE_COUNTS");
        result.addProperty("state_version", 1);
        result.addProperty("session_id", id);
        result.addProperty("readback_at", Instant.ofEpochSecond(now).toString());
        if (listing) paginate(request, rows, result, reserved, id);
        else count(rows, result, reserved);
        return result;
    }
    private static void paginate(JsonObject request, List<JsonObject> rows, JsonObject result, int reserved, String id) {
        int limit = request.has("limit") ? AdmissionLedger.number(request, "limit", 1, 200) : 100;
        int through = reserved, after = 0;
        if (request.has("cursor")) {
            String cursor = AdmissionLedger.text(request, "cursor", 256);
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
                if (parts.length != 4 || !parts[0].equals("1") || !parts[1].equals(id)
                    || !parts[2].matches("[0-9]{1,3}") || !parts[3].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                through = Integer.parseInt(parts[2]); after = Integer.parseInt(parts[3]);
                if (through > reserved || after < 1 || after >= through) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid state cursor"); }
        }
        JsonArray page = new JsonArray();
        int last = after;
        // Stay below the existing client's64KiB envelope, even for long URL/source pairs.
        int bytes = 1024;
        for (JsonObject row : rows) {
            int ordinal = row.get("ordinal").getAsInt();
            if (ordinal <= after || ordinal > through) continue;
            int size = row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (page.size() == limit || bytes + size > 60000) break;
            page.add(row); last = ordinal; bytes += size;
        }
        if (last == after && last < through) throw new IllegalStateException("State row exceeds response bound");
        result.add("rows", page);
        result.addProperty("through_ordinal", through);
        result.add("next_cursor", JsonNull.INSTANCE);
        if (last < through) result.addProperty("next_cursor", Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("1:" + id + ":" + through + ":" + last).getBytes(StandardCharsets.UTF_8)));
    }
    private static void count(List<JsonObject> rows, JsonObject result, int reserved) {
        JsonObject counts = new JsonObject();
        counts.addProperty("reserved", reserved);
        Map<String, String> names = Map.of("PENDING", "pending", "HELD", "held",
            "SCHEDULED_WAIT", "scheduled_wait", "UNSCHEDULED", "unscheduled", "UNKNOWN", "unknown");
        names.values().forEach(name -> counts.addProperty(name, 0));
        Long next = null;
        for (JsonObject row : rows) {
            JsonObject state = row.getAsJsonObject("scheduler");
            String name = names.get(state.get("state").getAsString());
            counts.addProperty(name, counts.get(name).getAsInt() + 1);
            if (!state.get("next_due_epoch").isJsonNull()) {
                long due = state.get("next_due_epoch").getAsLong();
                if (!state.get("lease_until_epoch").isJsonNull()) due = Math.max(due, state.get("lease_until_epoch").getAsLong());
                if (next == null || due < next) next = due;
            }
        }
        // Future scheduled work isn't necessarily a retry. Terminal reasons were not persisted.
        for (String name : List.of("done", "success", "terminal", "retry_wait")) counts.add(name, JsonNull.INSTANCE);
        result.add("counts", counts);
        result.add("next_attempt_at", next == null ? JsonNull.INSTANCE : new JsonPrimitive(Instant.ofEpochSecond(next).toString()));
    }
}
