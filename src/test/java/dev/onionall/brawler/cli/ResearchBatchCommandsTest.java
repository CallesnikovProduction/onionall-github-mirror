package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * The batch CLI validates a local intent without starting a session or contacting providers.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class ResearchBatchCommandsTest {
    private static final String EXAMPLE = """
        {
          "batchId": "technical-research-r200",
          "executionProfile": "bounded-research",
          "objectives": [{
            "id": "R200-001",
            "query": "Kotlin coroutine cancellation discussions",
            "intent": "FORUM_RESEARCH",
            "requirements": {"sourceTypes": ["FORUM"], "extract": ["THREADS", "POSTS", "CODE", "QUOTATIONS"]},
            "sourcePolicy": "approved-sources",
            "downloadPolicy": "DISCOVER_ONLY"
          }]
        }
        """;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void acceptsAnIntentWithoutGrantingApprovalOrCreatingArchiveState() throws Exception {
        var result = validate(EXAMPLE, 0);
        assertTrue(result.required("valid").asBoolean());
        assertEquals("technical-research-r200", result.required("batch_id").asText());
        assertEquals(1, result.required("objective_count").asInt());
        assertEquals("R200-001", result.required("objective_ids").get(0).asText());
    }

    @Test public void rejectsDuplicateKeysAndReportsStructuredFailure() throws Exception {
        var result = validate(EXAMPLE.replace("\"batchId\":", "\"batchId\": \"duplicate\", \"batchId\":"), 2);
        assertFalse(result.required("valid").asBoolean(true));
        assertEquals("INVALID", result.required("status").asText());
        assertFalse(result.required("error").required("code").asText().isBlank());
    }

    private JsonNode validate(String manifest, int expectedExit) throws Exception {
        Path work = temporary.newFolder().toPath();
        Path archive = work.resolve("archive");
        Path file = Files.writeString(work.resolve("batch.json"), manifest);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = Onionall.execute(new String[] {"--json", "--archive-dir", archive.toString(),
            "research", "batch", "validate", file.toString()}, new PrintWriter(out, true),
            new PrintWriter(err, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Batch validation must remain offline"); });

        assertEquals(out.toString() + err, expectedExit, code);
        assertEquals("", err.toString());
        assertFalse("Validation created persistent state", Files.exists(archive));
        JsonNode result = new ObjectMapper().readTree(out.toString());
        assertEquals("NOT_GRANTED", result.required("source_approval").asText());
        assertEquals("NOT_STARTED", result.required("execution").asText());
        assertEquals(0, result.required("network_requests").asInt(-1));
        return result;
    }
}
