package dev.onionall.brawler.research;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class ResearchBatchValidationTest {
    private static final String FORUM = """
        {
          "id": "R200-001",
          "query": "Kotlin coroutine cancellation discussions",
          "intent": "FORUM_RESEARCH",
          "requirements": {
            "sourceTypes": ["FORUM"],
            "extract": ["THREADS", "POSTS", "CODE", "QUOTATIONS"]
          },
          "sourcePolicy": "approved-sources",
          "downloadPolicy": "DISCOVER_ONLY"
        }
        """;

    @Test public void acceptsExplicitManifestWithoutGrantingAuthority() {
        var result = validate(batch(FORUM, ""));
        assertEquals(true, result.get("valid"));
        assertEquals("VALID", result.get("status"));
        assertEquals("technical-research-r200", result.get("batch_id"));
        assertEquals("bounded-research", result.get("execution_profile"));
        assertEquals(List.of("R200-001"), result.get("objective_ids"));
        assertEquals(1, result.get("objective_count"));
        assertEquals("approved-sources", result.get("source_policy"));
        assertEquals("DISCOVER_ONLY", result.get("download_policy"));
        assertFalse(result.toString().contains("cancellation discussions"));
        assertDryRun(result);
    }

    @Test public void rejectsDuplicateKeysTrailingTokensAndMalformedJson() {
        rejected(batch(FORUM, "") + " {}", "INVALID_JSON");
        rejected(batch(FORUM, "").replace("\"batchId\":", "\"batchId\":\"other\",\"batchId\":"), "INVALID_JSON");
        rejected(batch(FORUM.replace("\"sourceTypes\":", "\"sourceTypes\":[\"FORUM\"],\"sourceTypes\":"), ""),
            "INVALID_JSON");
        rejected("{\"batchId\":", "INVALID_JSON");
        error(ResearchBatchValidation.validate(new byte[]{(byte) 0xc3, 0x28}), "INVALID_ENCODING");
    }

    @Test public void rejectsUnknownAndUnsafeRequestsWithoutEchoingInput() {
        var unknown = rejected(batch(withField(FORUM, "\"private-field-marker\": \"value-marker\""), ""), "UNKNOWN_FIELD");
        assertFalse(unknown.toString().contains("private-field-marker"));
        assertFalse(unknown.toString().contains("value-marker"));
        rejected(batch(FORUM.replace("approved-sources", "any-source"), ""), "UNSUPPORTED_POLICY");
        rejected(batch(FORUM.replace("DISCOVER_ONLY", "DOWNLOAD_ALL"), ""), "UNSUPPORTED_POLICY");
        rejected(batch(FORUM.replace("FORUM_RESEARCH", "CUSTOM_INTENT"), ""), "UNSUPPORTED_VALUE");
        rejected(batch(FORUM.replace("\"FORUM\"", "\"DOCUMENTATION\""), ""), "INVALID_REQUIREMENTS");
        rejected(batch(FORUM.replace("R200-001", "../unsafe"), ""), "INVALID_ID");
        rejected(batch(FORUM.replace("cancellation discussions", "\\u0000"), ""), "INVALID_QUERY");
        rejected(batch(FORUM.replace("cancellation discussions", "\\uD800"), ""), "INVALID_QUERY");
    }

    @Test public void enforcesCaseInsensitiveUniqueIdsAndObjectiveCount() {
        rejected(batch(FORUM + "," + FORUM.replace("R200-001", "r200-001"), ""), "DUPLICATE_OBJECTIVE_ID");
        rejected(batch("", ""), "LIMIT_EXCEEDED");
        String full = IntStream.rangeClosed(1, 200).mapToObj(n -> FORUM.replace("R200-001", "R200-" + n))
            .collect(Collectors.joining(","));
        assertEquals(200, validate(batch(full, "")).get("objective_count"));
        rejected(batch(full + "," + FORUM.replace("R200-001", "R200-201"), ""), "LIMIT_EXCEEDED");
    }

    @Test public void retainsStreamOwnershipAndReadsOnlyOneOversizeByte() {
        byte[] oversize = new byte[ResearchBatchValidation.MAX_BYTES + 19];
        var stream = new ByteArrayInputStream(oversize) {
            @Override public void close() throws IOException {
                fail("Caller owns input stream");
            }
        };
        error(ResearchBatchValidation.validate(stream), "LIMIT_EXCEEDED");
        assertEquals(18, stream.available());
        error(ResearchBatchValidation.validate(oversize), "LIMIT_EXCEEDED");
    }

    @Test public void enforcesSharedPagesAndPerObjectiveBounds() {
        String first = withField(FORUM, "\"budget\":{\"globalPages\":3,\"pagesPerSeed\":2,\"depth\":2," +
            "\"totalSeconds\":300,\"sliceSeconds\":90,\"requestTimeoutMs\":1500}");
        String second = withField(FORUM.replace("R200-001", "R200-002"), "\"budget\":{\"globalPages\":5}");
        var result = validate(batch(first + "," + second, ",\"budget\":{\"globalPages\":8}"));
        assertEquals(true, result.get("valid"));
        assertEquals(8, result.get("allocated_global_pages"));
        assertDryRun(result);
        rejected(batch(first + "," + second, ",\"budget\":{\"globalPages\":7}"), "INVALID_BUDGET");
        rejected(batch(FORUM, ",\"budget\":{\"globalPages\":7}"), "INVALID_BUDGET");
        rejected(batch(first.replace("\"depth\":2", "\"depth\":4"), ""), "INVALID_BUDGET");
        rejected(batch(first.replace("\"pagesPerSeed\":2", "\"pagesPerSeed\":4"), ""), "INVALID_BUDGET");
        rejected(batch(first.replace("\"totalSeconds\":300", "\"totalSeconds\":120"), ""), "INVALID_BUDGET");
        rejected(batch(first.replace("\"globalPages\":3", "\"globalPages\":3.0"), ""), "INVALID_BUDGET");
    }

    private static String batch(String objectives, String extra) {
        return "{\"batchId\":\"technical-research-r200\",\"executionProfile\":\"bounded-research\",\"objectives\":[" +
            objectives + "]" + extra + "}";
    }

    private static String withField(String objective, String field) {
        return objective.substring(0, objective.lastIndexOf('}')) + "," + field + "}";
    }

    private static Map<String, Object> validate(String json) {
        return ResearchBatchValidation.validate(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> rejected(String json, String code) {
        var result = validate(json);
        error(result, code);
        return result;
    }

    private static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("valid"));
        assertEquals("INVALID", result.get("status"));
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertDryRun(result);
    }

    private static void assertDryRun(Map<String, Object> result) {
        assertEquals("NOT_GRANTED", result.get("source_approval"));
        assertEquals("NOT_STARTED", result.get("execution"));
        assertEquals(0, result.get("network_requests"));
        assertEquals(0, result.get("persistent_writes"));
    }
}
