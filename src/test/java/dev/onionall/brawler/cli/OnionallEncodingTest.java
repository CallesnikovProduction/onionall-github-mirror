package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.archive.ArchiveStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * Checks the actual main process encoding with redirected output and a legacy console charset.
 *
 * @author Стефан Колесников
 * @author GPT-6.2 Sol
 */
public class OnionallEncodingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 20_000)
    public void redirectedJsonIsUtf8RegardlessOfTheConsoleCharset() throws Exception {
        Path work = temporary.newFolder().toPath();
        Path stdout = work.resolve("stdout.json");
        Path args = work.resolve("jvm.args");
        String classpath = System.getProperty("surefire.test.class.path",
            System.getProperty("java.class.path")).replace('\\', '/').replace("\"", "\\\"");
        Files.writeString(args, "-Dstdout.encoding=windows-1251\n-cp\n\"" + classpath
            + "\"\ndev.onionall.brawler.cli.Onionall\n");
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String title = "Проверка Unicode Ω";
        String seed = "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
        Path archive = work.resolve("archive");
        String pageId;
        // Use stored UTF-8 data so platform argv encoding cannot mask the stdout contract.
        try (var store = new ArchiveStore(archive)) {
            String plan = (String) store.createPlan(List.of(seed), 0, 1, "encoding fixture").get("plan_id");
            store.approvePlan(plan);
            byte[] html = ("<html><title>" + title + "</title><body>fixture</body></html>")
                .getBytes(StandardCharsets.UTF_8);
            pageId = (String) store.importHtml(plan, seed, html, List.of()).get("page_id");
        }
        var process = new ProcessBuilder(java.toString(), "@" + args, "--json",
            "--archive-dir", archive.toString(), "page", "show", pageId)
            .redirectOutput(stdout.toFile()).redirectError(work.resolve("stderr.txt").toFile()).start();
        try {
            assertTrue("Offline CLI did not exit", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(stdout))).toString();
            var document = new ObjectMapper().readTree(text);
            assertEquals(title, document.required("title").asText());
            assertEquals("MANUAL_IMPORT", document.required("source_kind").asText());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
