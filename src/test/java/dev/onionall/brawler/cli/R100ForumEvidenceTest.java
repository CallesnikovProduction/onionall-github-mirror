package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * R100 archived forum evidence through the production CLI and a disposable H2 catalog.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class R100ForumEvidenceTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/topic/42";
    private static final String FIRST_TEXT = "Keep two recovery copies.";
    private static final String SECOND_TEXT = "A repair must preserve the original.";
    private static final String MULTILINGUAL_TEXT = "Проверка резервных копий. 日本語の確認。 Café prêt.";
    private static final String POSTED_AT = "2026-01-02T10:15:00+03:00";
    private static final String HTML = """
        <!doctype html>
        <html><head><meta charset="UTF-8"><title>Archive recovery discussion</title></head><body>
        <div class="post" id="p101"><div class="postbody">
          <p class="author"><a class="username">Elena</a><time datetime="%s">Posted date</time></p>
          <div class="content"><p>%s</p></div>
        </div></div>
        <div class="post" id="p109"><div class="postbody">
          <p class="author"><a class="username">Ravi</a> on a displayed day</p>
          <div class="content">
            <blockquote><cite>Elena wrote:</cite><p>%s</p>
              <div class="post" id="p999"><div class="postbody">
                <div class="content">Retained markup inside the quotation.</div>
              </div></div>
            </blockquote>
            <p>%s</p>
          </div>
        </div></div>
        <div class="post" id="p105"><div class="postbody">
          <div class="content"><p>%s</p></div>
        </div></div>
        </body></html>
        """.formatted(POSTED_AT, FIRST_TEXT, FIRST_TEXT, SECOND_TEXT, MULTILINGUAL_TEXT);
    private static final ObjectMapper JSON = new ObjectMapper();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path archive;
    private String session;
    private String job;
    private String page;
    private String snapshot;
    private String hash;

    @Before public void importAnApprovedThreadSnapshot() throws Exception {
        Path work = temporary.newFolder().toPath();
        archive = work.resolve("archive");
        var plan = cli("research", "thread", "plan", URL, "--max-pages", "1");
        assertEquals("THREAD", plan.required("mode").asText());
        assertFalse(plan.required("approved").asBoolean());
        session = plan.required("session_id").asText();
        job = plan.required("seeds").get(0).required("job_id").asText();
        assertTrue(cli("research", "approve", session).required("approved").asBoolean());

        Path input = Files.writeString(work.resolve("discussion.html"), HTML, StandardCharsets.UTF_8);
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        var receipt = cli("archive", "import", input.toString(), "--plan", job, "--url", URL);
        assertEquals("MANUAL_IMPORT", receipt.required("import_mode").asText());
        assertEquals(hash, receipt.required("content_hash").asText());
        page = receipt.required("page_id").asText();
        snapshot = receipt.required("snapshot_id").asText();
    }

    @Test(timeout = 15_000)
    public void f01PreservesThreeObservedPostIdsInDomOrder() throws Exception {
        var posts = observedPosts();
        assertEquals(3, posts.size());
        var ids = new ArrayList<String>();
        var observationIds = new HashSet<String>();
        for (var post : posts) {
            ids.add(post.required("source_post_id").asText());
            observationIds.add(post.required("post_id").asText());
            assertEquals("INTERNAL_OBSERVATION_ID", post.required("post_id_basis").asText());
        }
        assertEquals(List.of("p101", "p109", "p105"), ids);
        assertEquals(3, observationIds.size());
        for (var post : posts) {
            assertFalse(ids.contains(post.required("post_id").asText()));
        }
    }

    @Test(timeout = 15_000)
    public void f03UsesExplicitAuthorAndDatetimeWithoutGuessingMissingFields() throws Exception {
        var posts = observedPosts();
        var first = posts.get(0);
        assertEquals("Elena", first.required("author").asText());
        assertEquals(POSTED_AT, first.required("posted_at").asText());
        assertEquals("RAW_TIME_DATETIME_ATTRIBUTE", first.required("evidence").required("posted_at_basis").asText());
        assertEquals(FIRST_TEXT, first.required("text").asText());

        var second = posts.get(1);
        assertEquals("Ravi", second.required("author").asText());
        assertTrue(second.required("posted_at").isNull());
        assertTrue(posts.get(2).required("author").isNull());
        assertTrue(posts.get(2).required("posted_at").isNull());
    }

    @Test(timeout = 15_000)
    public void f05RetainsQuotedEvidenceWithoutCreatingAnotherPost() throws Exception {
        var posts = observedPosts();
        assertEquals(3, posts.size());
        assertEquals("p109", posts.get(1).required("source_post_id").asText());
        assertEquals(SECOND_TEXT, posts.get(1).required("text").asText());
        var quotes = posts.get(1).required("quotes");
        assertEquals(1, quotes.size());
        assertEquals("Elena wrote:", quotes.get(0).required("citation_text").asText());
        assertTrue(quotes.get(0).required("text").asText().contains(FIRST_TEXT));
        assertTrue(quotes.get(0).required("text").asText().contains("Retained markup inside the quotation."));
        for (var post : posts) assertNotEquals("p999", post.required("source_post_id").asText());
    }

    @Test(timeout = 15_000)
    public void f08PreservesMultilingualSourceTextAndPostBoundaries() throws Exception {
        var posts = observedPosts();
        var text = new ArrayList<String>();
        for (var post : posts) text.add(post.required("text").asText());
        assertEquals(List.of(FIRST_TEXT, SECOND_TEXT, MULTILINGUAL_TEXT), text);
    }

    @Test(timeout = 15_000)
    public void f02MergesOverlappingPostsAcrossFiveCapturedPages() throws Exception {
        String topic = URL.replace("/42", "/43");
        var plan = cli("research", "thread", "plan", topic + "?page=1", "--max-pages", "5");
        String threadSession = plan.required("session_id").asText();
        String threadJob = plan.required("seeds").get(0).required("job_id").asText();
        assertFalse(plan.required("approved").asBoolean());
        assertTrue(cli("research", "approve", threadSession).required("approved").asBoolean());
        Path work = temporary.newFolder().toPath();
        var snapshots = new HashSet<String>();
        // Import in reverse order to distinguish numeric ordering from capture order.
        for (int number = 5; number >= 1; number--) {
            String next = number < 5 ? "<a rel='next' href='?page=" + (number + 1) + "'>Next</a>" : "";
            Path input = Files.writeString(work.resolve("page-" + number + ".html"), """
                <!doctype html><title>Five-page thread</title>
                <div class="post" id="p200"><div class="postbody">
                  <div class="content">Repeated message across pagination.</div>
                </div></div>
                <div class="post" id="p20%d"><div class="postbody">
                  <div class="content">Distinct message from page %d.</div>
                </div></div>
                %s
                """.formatted(number, number, next));
            var receipt = cli("archive", "import", input.toString(), "--plan", threadJob,
                "--url", topic + "?page=" + number);
            assertEquals("MANUAL_IMPORT", receipt.required("import_mode").asText());
            assertTrue(snapshots.add(receipt.required("snapshot_id").asText()));
        }

        var result = cli("research", "thread", "show", threadSession);
        var threads = result.required("threads");
        assertEquals(1, threads.size());
        var thread = threads.get(0);
        assertEquals(5, thread.required("captured_pages").size());
        assertEquals("VERIFIED_NUMERIC_PAGINATION", thread.required("ordering_status").asText());
        assertEquals("OBSERVED_PAGINATION_CAPTURED", thread.required("completeness_status").asText());
        assertFalse(thread.required("full_thread_coverage_verified").asBoolean());
        var pagination = thread.required("observed_pagination");
        assertEquals(4, pagination.size());
        for (var edge : pagination) assertTrue(edge.required("captured").asBoolean());

        var posts = thread.required("posts");
        var ids = new ArrayList<String>();
        for (var post : posts) ids.add(post.required("source_post_id").asText());
        assertEquals(List.of("p200", "p201", "p202", "p203", "p204", "p205"), ids);
        var repeatedSources = new HashSet<String>();
        for (var source : posts.get(0).required("provenance")) {
            assertEquals(threadJob, source.required("job_id").asText());
            assertEquals("MANUAL_IMPORT", source.required("source_kind").asText());
            assertTrue(source.required("fetched_at").isNull());
            assertTrue(repeatedSources.add(source.required("snapshot_id").asText()));
        }
        assertEquals(snapshots, repeatedSources);
        assertEquals(threads, cli("research", "thread", "show", threadSession).required("threads"));
    }

    private JsonNode observedPosts() throws IOException {
        var result = cli("research", "thread", "show", session);
        assertEquals(session, result.required("session_id").asText());
        assertFalse(result.required("scheduler_readback_current").asBoolean());
        assertNotEquals("COMPLETED", result.required("session_status").asText());

        var pages = result.required("pages");
        assertEquals(1, pages.size());
        var archived = pages.get(0);
        assertEquals(URL, archived.required("canonical_url").asText());
        assertEquals(page, archived.required("page_id").asText());
        assertEquals(job, archived.required("job_id").asText());
        assertEquals(snapshot, archived.required("snapshot_id").asText());
        assertEquals(hash, archived.required("content_hash").asText());
        assertEquals("MANUAL_IMPORT", archived.required("source_kind").asText());
        assertTrue(archived.required("fetched_at").isNull());
        assertEquals(0, archived.required("http_status").asInt(-1));
        String recorded = archived.required("recorded_at").asText();
        assertFalse(recorded.isBlank());

        var threads = result.required("threads");
        assertEquals(1, threads.size());
        var thread = threads.get(0);
        assertEquals("STRUCTURED_POSTS", thread.required("extraction_status").asText());
        var posts = thread.required("posts");
        for (var post : posts) {
            assertProvenance(post, recorded);
            var provenance = post.required("provenance");
            assertEquals(1, provenance.size());
            assertProvenance(provenance.get(0), recorded);
        }

        // Every CLI call opens and closes its catalog; read persisted evidence through a fresh call.
        var reopened = cli("research", "thread", "show", session);
        assertEquals(threads, reopened.required("threads"));
        return posts;
    }

    private void assertProvenance(JsonNode item, String recorded) {
        assertEquals(URL, item.required("source_page_url").asText());
        assertEquals(job, item.required("job_id").asText());
        assertEquals(page, item.required("source_page_id").asText());
        assertEquals(snapshot, item.required("snapshot_id").asText());
        assertEquals(recorded, item.required("recorded_at").asText());
        assertEquals("MANUAL_IMPORT", item.required("source_kind").asText());
        assertTrue(item.required("fetched_at").isNull());
    }

    private JsonNode cli(String... command) throws IOException {
        var args = new ArrayList<>(List.of("--json", "--archive-dir", archive.toString()));
        args.addAll(List.of(command));
        var output = new StringWriter();
        var errors = new StringWriter();
        int exit = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(errors, true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Archived R100 forum evidence must not fetch"); });
        assertEquals(String.join(" ", command) + "\n" + output + errors, 0, exit);
        assertEquals("", errors.toString());
        return JSON.readTree(output.toString());
    }
}
