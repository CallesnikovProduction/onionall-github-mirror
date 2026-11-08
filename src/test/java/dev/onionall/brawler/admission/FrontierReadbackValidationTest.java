package dev.onionall.brawler.admission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Strict v1 contract tests without a network or an alternate scheduler. */
public class FrontierReadbackValidationTest {
    private static final String ORIGIN = "http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion";

    private static Map<String, Object> plan() {
        return new LinkedHashMap<>(Map.of("plan_id", "session", "origin", ORIGIN, "approved", true,
            "max_pages", 3, "depth", 2, "seeds", List.of(ORIGIN + "/a", ORIGIN + "/b")));
    }

    private static Map<String, Object> session() {
        var spec = new LinkedHashMap<String, Object>(Map.of("session_id", "session", "origin", ORIGIN,
            "approved", true, "max_pages", 3, "max_depth", 2, "policy_version", 1,
            "seeds", List.of(ORIGIN + "/a", ORIGIN + "/b")));
        return new LinkedHashMap<>(Map.of("outcome", "SESSION_OPEN", "reserved_count", 2, "sealed", false, "spec", spec));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(Map<String, Object> session) {
        return (Map<String, Object>) session.get("spec");
    }

    private static String cursor(int through, int after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("1:session:" + through + ":" + after).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> row(int ordinal, String state) {
        var scheduler = new LinkedHashMap<String, Object>();
        scheduler.put("state", state);
        scheduler.put("next_due_epoch", state.equals("UNSCHEDULED") ? null : 17);
        scheduler.put("lease_until_epoch", state.equals("HELD") ? 23 : null);
        return new LinkedHashMap<>(Map.of("session_id", "session", "ordinal", ordinal,
            "reservation_id", UUID.nameUUIDFromBytes(Integer.toString(ordinal).getBytes(StandardCharsets.UTF_8)).toString(),
            "url", ORIGIN + "/" + ordinal, "source_url", "", "depth", 0, "scheduler", scheduler));
    }

    private static Map<String, Object> page(int through, int after, int size, String state) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int last = Math.min(through, after + size);
        for (int i = after + 1; i <= last; i++) rows.add(row(i, state));
        var page = new LinkedHashMap<String, Object>(Map.of("outcome", "STATE_LIST", "state_version", 1,
            "session_id", "session", "readback_at", "2026-11-07T00:00:00Z", "through_ordinal", through, "rows", rows));
        page.put("next_cursor", last == through ? null : cursor(through, last));
        return page;
    }

    private static Map<String, Object> counts(int reserved) {
        var counts = new LinkedHashMap<String, Object>(Map.of("reserved", reserved, "pending", 0,
            "held", 0, "scheduled_wait", 0, "unscheduled", reserved, "unknown", 0));
        for (String key : List.of("done", "success", "terminal", "retry_wait")) counts.put(key, null);
        var reply = new LinkedHashMap<String, Object>(Map.of("outcome", "STATE_COUNTS", "state_version", 1,
            "session_id", "session", "readback_at", "2026-11-07T00:00:00Z", "counts", counts));
        reply.put("next_attempt_at", null);
        return reply;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counters(Map<String, Object> reply) {
        return (Map<String, Object>) reply.get("counts");
    }

    @Test public void sessionRejectsNumericTruncationMalformedLocalPlansAndUnsupportedPolicy() throws Exception {
        FrontierReadback.verifySession(session(), plan());
        for (Object value : Arrays.asList(3.5, 4294967299L, "3", null, 0, 101)) {
            var reply = session();
            spec(reply).put("max_pages", value);
            assertThrows("Server max_pages=" + value, IOException.class,
                () -> FrontierReadback.verifySession(reply, plan()));
            var malformed = plan();
            malformed.put("max_pages", value);
            assertThrows("Local max_pages=" + value, IOException.class,
                () -> FrontierReadback.verifySession(session(), malformed));
        }
        for (Object value : Arrays.asList(2.9, 4294967298L, "2", null, -1, 6)) {
            var reply = session();
            spec(reply).put("max_depth", value);
            assertThrows(IOException.class, () -> FrontierReadback.verifySession(reply, plan()));
            var malformed = plan();
            malformed.put("depth", value);
            assertThrows(IOException.class, () -> FrontierReadback.verifySession(session(), malformed));
        }
        for (String key : List.of("plan_id", "origin", "seeds", "approved", "depth", "max_pages")) {
            var malformed = plan();
            malformed.remove(key);
            assertThrows(key, IOException.class, () -> FrontierReadback.verifySession(session(), malformed));
        }
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(null, plan()));
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(session(), null));
        var wrongPolicy = session(); spec(wrongPolicy).put("policy_version", 2);
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(wrongPolicy, plan()));
        var unapproved = plan(); unapproved.put("approved", false);
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(session(), unapproved));
        var overflow = session(); overflow.put("reserved_count", 4);
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(overflow, plan()));
    }

    @Test public void seedEqualityMatchesServerCanonicalSetButRejectsMalformedAuthoritativeDuplicates() throws Exception {
        var local = plan();
        local.put("seeds", List.of(ORIGIN + "/b", ORIGIN + "/a", ORIGIN + "/b"));
        FrontierReadback.verifySession(session(), local);
        for (Object malformed : Arrays.asList(List.of(), List.of(1, 2), Arrays.asList(ORIGIN + "/a", null),
                List.of("", ORIGIN + "/b"), List.of(ORIGIN + "/a", ORIGIN + "/changed"))) {
            var badPlan = plan(); badPlan.put("seeds", malformed);
            assertThrows(IOException.class, () -> FrontierReadback.verifySession(session(), badPlan));
            var badSession = session(); spec(badSession).put("seeds", malformed);
            assertThrows(IOException.class, () -> FrontierReadback.verifySession(badSession, plan()));
        }
        var duplicate = session();
        spec(duplicate).put("seeds", List.of(ORIGIN + "/a", ORIGIN + "/a", ORIGIN + "/b"));
        assertThrows(IOException.class, () -> FrontierReadback.verifySession(duplicate, plan()));
    }

    @Test public void v1SessionQuotaAndPageSizeAreIndependentAndMoreThan100IsUnsupported() throws Exception {
        var local = plan(); local.put("max_pages", 100);
        var reply = session(); spec(reply).put("max_pages", 100); reply.put("reserved_count", 100);
        FrontierReadback.verifySession(reply, local);
        assertEquals(100, FrontierReadback.page(page(100, 0, 100, "UNSCHEDULED"), "session", null, 200).rows().size());
        assertEquals(100, FrontierReadback.counts(counts(100), "session").reserved());
        assertThrows(IOException.class, () -> FrontierReadback.page(page(101, 0, 100, "UNSCHEDULED"), "session", null, 200));
        assertThrows(IOException.class, () -> FrontierReadback.counts(counts(101), "session"));
        for (int limit : new int[]{0, 201}) {
            assertThrows(IOException.class, () -> FrontierReadback.page(page(1, 0, 1, "UNSCHEDULED"), "session", null, limit));
        }
    }

    @Test public void paginationRetainsMembershipWhileSchedulerStateAndServerClockMayChange() throws Exception {
        String cursor = null;
        Set<Integer> seen = new LinkedHashSet<>();
        int calls = 0;
        do {
            var reply = page(100, seen.size(), 7, calls % 2 == 0 ? "PENDING" : "HELD");
            // A later page can observe a clock adjustment and a different scheduler state.
            reply.put("readback_at", calls++ % 2 == 0 ? "2099-01-01T00:00:00Z" : "1970-01-01T00:00:00Z");
            var result = FrontierReadback.page(reply, "session", cursor, 7);
            assertEquals(100, result.throughOrdinal());
            for (var row : result.rows()) assertTrue(seen.add(row.ordinal()));
            cursor = result.nextCursor();
        } while (cursor != null);
        assertEquals(100, seen.size());
        assertEquals(15, calls);
        var moved = page(3, 2, 1, "PENDING");
        assertThrows(IOException.class, () -> FrontierReadback.page(moved, "session", cursor(2, 1), 2));
    }

    @Test public void paginationRejectsInvalidCursorsGapsDuplicateIdentityAndContradictoryScheduler() throws Exception {
        for (String cursor : List.of("!", "", "x".repeat(257), cursor(3, 0), cursor(3, 3), cursor(2, 1))) {
            assertThrows(cursor, IOException.class,
                () -> FrontierReadback.page(page(3, 1, 2, "PENDING"), "session", cursor, 2));
        }
        var gap = page(3, 0, 2, "PENDING"); gap.put("rows", List.of(row(1, "PENDING"), row(3, "PENDING")));
        assertThrows(IOException.class, () -> FrontierReadback.page(gap, "session", null, 2));
        var duplicate = page(2, 0, 2, "PENDING");
        var repeated = row(1, "PENDING"); repeated.put("ordinal", 2);
        duplicate.put("rows", List.of(row(1, "PENDING"), repeated));
        assertThrows(IOException.class, () -> FrontierReadback.page(duplicate, "session", null, 2));
        var empty = page(3, 0, 2, "PENDING"); empty.put("rows", List.of());
        assertThrows(IOException.class, () -> FrontierReadback.page(empty, "session", null, 2));
        for (String state : List.of("DONE", "PENDING", "HELD", "UNSCHEDULED")) {
            var invalid = page(1, 0, 1, state);
            var item = row(1, state);
            @SuppressWarnings("unchecked") var scheduler = (Map<String, Object>) item.get("scheduler");
            if (state.equals("PENDING")) scheduler.put("next_due_epoch", null);
            if (state.equals("HELD")) scheduler.put("lease_until_epoch", null);
            if (state.equals("UNSCHEDULED")) scheduler.put("next_due_epoch", 1);
            invalid.put("rows", List.of(item));
            assertThrows(state, IOException.class, () -> FrontierReadback.page(invalid, "session", null, 1));
        }
    }

    @Test public void unsupportedTerminalCountersVersionsAndInconsistentCountsNeverBecomeCompletionEvidence() throws Exception {
        var unknown = FrontierReadback.counts(counts(100), "session");
        assertEquals(100, unknown.unscheduled());
        assertNull(unknown.done()); assertNull(unknown.success()); assertNull(unknown.terminal()); assertNull(unknown.retryWait());
        for (String field : List.of("done", "success", "terminal", "retry_wait")) {
            var unsupported = counts(1); counters(unsupported).put(field, 0);
            assertThrows(field, IOException.class, () -> FrontierReadback.counts(unsupported, "session"));
        }
        var inconsistent = counts(3); counters(inconsistent).put("pending", 1);
        assertThrows(IOException.class, () -> FrontierReadback.counts(inconsistent, "session"));
        var inventedNext = counts(0); inventedNext.put("next_attempt_at", "2099-01-01T00:00:00Z");
        assertThrows(IOException.class, () -> FrontierReadback.counts(inventedNext, "session"));
        for (Object version : Arrays.asList(2, "1", null, 1.5)) {
            var future = counts(1); future.put("state_version", version);
            assertThrows(IOException.class, () -> FrontierReadback.counts(future, "session"));
        }
        var malformedTime = counts(1); malformedTime.put("readback_at", "not-an-instant");
        assertThrows(IOException.class, () -> FrontierReadback.counts(malformedTime, "session"));
        assertEquals(Instant.parse("2026-11-07T00:00:00Z"), FrontierReadback.counts(counts(1), "session").readbackAt());
    }
}
