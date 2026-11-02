package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic CLI tests: no Tor, no real socket and no terminal required. */
public class OnionallTest {
    record Result(int code, String out, String err) {
        JsonNode json() throws IOException { return new ObjectMapper().readTree(out); }
    }
    private Result run(Onionall.Transport transport, String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        int code = Onionall.execute(args, new PrintWriter(out, true), new PrintWriter(err, true),
                false, Map.of("NO_COLOR", ""), transport);
        assertFalse(out.toString().contains("\u001b"));
        assertFalse(err.toString().contains("\u001b"));
        return new Result(code, out.toString(), err.toString());
    }
    private final Onionall.Transport unused = (c, u) -> { throw new AssertionError("Unexpected network request"); };
    private static ProtocolResponse html(int status, String body) {
        Metadata md = new Metadata(); md.setValue("content-type", "text/html; charset=UTF-8");
        return new ProtocolResponse(body.getBytes(StandardCharsets.UTF_8), status, md);
    }
    private static void clean(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test public void helpVersionAndNonInteractiveWelcomeNeverOpenNetworkOrReadInput() {
        Result help = run(unused, "--help"); assertEquals(0, help.code());
        assertTrue(help.out().contains("fetch")); assertTrue(help.out().contains("inspect"));
        Result version = run(unused, "--version"); assertEquals(0, version.code());
        assertTrue(version.out().contains("onionall 0.1.0"));
        Result noArgs = run(unused); assertEquals(0, noArgs.code());
        assertTrue(noArgs.out().contains("Usage:")); assertFalse(noArgs.out().contains("Choose >"));
    }
    @Test public void jsonHelpVersionAndWelcomeStayMachineReadable() throws Exception {
        for(String[] args:List.of(new String[]{"--json"},new String[]{"--help","--json"},new String[]{"fetch","--help","--json"},new String[]{"--version","--json"})) {
            Result r=run(unused,args);assertEquals(0,r.code());assertTrue(r.json().has("commands"));
        }
    }
    @Test public void assignedJsonFlagAlsoProducesJsonForParsingFailures() throws Exception {
        for(String[] args:List.of(new String[]{"fetch","--json=true"},new String[]{"--json=true","bogus"})) {
            Result r=run(unused,args);assertEquals(2,r.code());assertEquals(2,r.json().path("exit_code").asInt());
        }
    }
    @Test public void malformedArgumentsAndUnknownCommandsHaveUsageExitAndJsonErrors() throws Exception {
        for (String[] args : List.of(new String[]{"bogus", "--json"}, new String[]{"fetch", "--json"},
                new String[]{"fetch", OnionSmoke.URL, "--socks-port", "not-a-number", "--json"})) {
            Result r = run(unused, args); assertEquals(2, r.code());
            assertFalse(r.json().path("passed").asBoolean()); assertEquals(2, r.json().path("exit_code").asInt());
        }
    }
    @Test public void invalidScopeIsRejectedBeforeTransport() throws Exception {
        for (String url : List.of("http://localhost/", "file:///etc/passwd", "https://example.com/",
                "http://invalid.onion/", "http://" + OnionSmoke.HOST + ":7071/",
                "http://user:pass@" + OnionSmoke.HOST + "/", "http://" + OnionSmoke.HOST + "/a/../blocked")) {
            Result r = run(unused, "fetch", url, "--json");
            assertEquals(url, 3, r.code()); assertEquals(3, r.json().path("exit_code").asInt());
        }
    }
    @Test public void configurationBoundsAreCheckedBeforeTransport() {
        for (String[] options : List.of(new String[]{"--timeout-ms", "0"}, new String[]{"--timeout-ms", "120001"},
                new String[]{"--max-bytes", "0"}, new String[]{"--max-bytes", "131073"},
                new String[]{"--socks-port", "0"}, new String[]{"--socks-port", "65536"})) {
            assertEquals(2, run(unused, "fetch", OnionSmoke.URL, options[0], options[1], "--json").code());
        }
    }
    @Test public void validFetchPropagatesBoundedPolicyAndSavesWholeHtml() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            Path target = dir.resolve("folder with space/page.html"); AtomicInteger calls = new AtomicInteger();
            String body = "<!doctype html><html><title>Safe &amp; Sound</title></html>";
            Result r = run((c, u) -> {
                calls.incrementAndGet(); assertEquals(OnionSmoke.URL, u);
                assertEquals("ONLY", c.get("tor.mode")); assertEquals("127.0.0.1", c.get("tor.socks.host"));
                assertEquals(19051, c.get("tor.socks.port")); assertEquals(1234L, c.get("http.timeout"));
                assertEquals(4096, c.get("http.content.limit")); assertEquals(true, c.get("tor.discovery.html.only"));
                assertEquals(List.of(OnionSmoke.HOST), c.get("tor.allowed.hosts"));
                return html(200, body);
            }, "fetch", OnionSmoke.URL + "#fragment", "--socks-port", "19051", "--timeout-ms", "1234",
                    "--max-bytes", "4096", "--output", target.toString(), "--json");
            assertEquals(0, r.code()); assertEquals(1, calls.get()); assertTrue(r.json().path("passed").asBoolean());
            assertEquals("Safe & Sound", r.json().path("title").asText());
            assertEquals(body, Files.readString(target)); assertEquals(target.toString(), r.json().path("saved").asText());
            assertTrue(r.json().path("elapsed_seconds").asDouble() >= 0);
        } finally { clean(dir); }
    }
    @Test public void outputExistsIsRejectedBeforeNetworkAndOverwriteIsExplicit() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            Path target = dir.resolve("page.html"); Files.writeString(target, "original");
            assertEquals(6, run(unused, "fetch", OnionSmoke.URL, "--output", target.toString(), "--json").code());
            assertEquals("original", Files.readString(target));
            Result r = run((c, u) -> html(200, "<!doctype html><title>New</title>"), "fetch", OnionSmoke.URL,
                    "--output", target.toString(), "--overwrite", "--json");
            assertEquals(0, r.code()); assertTrue(Files.readString(target).contains("New"));
        } finally { clean(dir); }
    }
    @Test public void redirectAndErrorResponsesNeverSaveOrFollow() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            for (int code : List.of(302, 304, 404, 503)) {
                AtomicInteger calls = new AtomicInteger();Path target = dir.resolve(code + ".html");
                Result r = run((c, u) -> { calls.incrementAndGet();return html(code, "<html>error</html>"); },
                        "fetch", OnionSmoke.URL, "--output", target.toString(), "--json");
                assertEquals(5, r.code()); assertEquals(code, r.json().path("http_status").asInt());
                assertEquals(1, calls.get());assertFalse(Files.exists(target));
            }
        } finally { clean(dir); }
    }
    @Test public void binaryEmptyAndOverLimitResponsesAreRejectedWithoutSaving() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            Metadata binary = new Metadata();binary.setValue("content-type", "application/octet-stream");
            for (ProtocolResponse response : List.of(new ProtocolResponse(new byte[]{1},200,binary),
                    html(200,""), html(200,"x".repeat(129)))) {
                Path target = dir.resolve("page.html");
                Result r = run((c,u)->response,"fetch",OnionSmoke.URL,"--max-bytes","128","--output",target.toString(),"--json");
                assertEquals(5,r.code());assertFalse(Files.exists(target));
            }
        } finally { clean(dir); }
    }
    @Test public void transportFailureKeepsCauseAndReturnsNetworkExit() throws Exception {
        Path dir=Files.createTempDirectory("onionall-failure-");
        try {
            Result r = run((c,u)->{throw new IOException("SOCKS_FAILURE",new IOException("connection refused"));},
                    "fetch",OnionSmoke.URL,"--output",dir.resolve("absent.html").toString(),"--json");
            assertEquals(4,r.code());assertTrue(r.json().path("errors").toString().contains("connection refused"));
            assertFalse(Files.exists(dir.resolve("absent.html")));
        } finally { clean(dir); }
    }
    @Test public void plainNoColorOutputSanitizesRemoteControlCharacters() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            String title = "<!doctype html><title>remote\u001b[31m\u202eDATA</title>";
            Result r = run((c,u)->html(200,title),"fetch",OnionSmoke.URL,"--output",dir.resolve("a.html").toString(),"--no-color");
            assertEquals(0,r.code());assertFalse(r.out().contains("\u202e"));assertTrue(r.out().contains("DATA"));
        } finally { clean(dir); }
    }
    @Test public void inspectCountsLinksAndParsesDeclaredEncodingWithoutTransport() throws Exception {
        Path dir = Files.createTempDirectory("onionall-cli-");
        try {
            String body = "<html><head><meta charset='windows-1252'><title>Caf\u00e9</title><meta name='description' content='Offline'></head>"
                    + "<a href='/relative'>r</a><a href='/relative'>duplicate</a><a href='https://example.com/'>a</a>"
                    + "<a href='http://"+OnionSmoke.HOST+"/'>o</a><a href='//"+OnionSmoke.HOST+"/'>o2</a></html>";
            Path file = dir.resolve("input.html");Files.write(file,body.getBytes(java.nio.charset.Charset.forName("windows-1252")));
            Result r = run(unused,"inspect",file.toString(),"--json");assertEquals(0,r.code());JsonNode j=r.json();
            assertEquals("Caf\u00e9",j.path("title").asText());assertEquals("Offline",j.path("description").asText());
            assertEquals(5,j.path("anchors").asInt());assertEquals(4,j.path("unique_links").asInt());
            assertEquals(2,j.path("relative_links").asInt());assertEquals(3,j.path("absolute_links").asInt());
            assertEquals(2,j.path("onion_links").asInt());assertEquals("windows-1252",j.path("encoding").asText());
        } finally { clean(dir); }
    }
    @Test public void inspectMissingAndOversizedInputHaveFileExit() throws Exception {
        Path dir=Files.createTempDirectory("onionall-cli-");
        try {
            assertEquals(6,run(unused,"inspect",dir.resolve("missing").toString(),"--json").code());
            Path file=dir.resolve("large.html");Files.write(file,new byte[(int)HtmlFiles.INSPECT_LIMIT+1]);
            assertEquals(6,run(unused,"inspect",file.toString(),"--json").code());
        } finally { clean(dir); }
    }
    @Test public void atomicPublishDoesNotClobberRacingWriterOrLeavePartialFiles() throws Exception {
        Path dir=Files.createTempDirectory("onionall-cli-");
        try {
            Path target=dir.resolve("page.html");Files.writeString(target,"winner");
            assertThrows(FileAlreadyExistsException.class,()->HtmlFiles.save("loser".getBytes(StandardCharsets.UTF_8),target,false));
            assertEquals("winner",Files.readString(target));
            try(var files=Files.list(dir)){assertEquals(List.of(target),files.toList());}
        } finally { clean(dir); }
    }
    @Test public void brandHasCompactMonochromeVariantAndNoRemoteControlCharacters() {
        StringWriter compact=new StringWriter();Brand.welcome(new PrintWriter(compact),false,35);
        assertTrue(compact.toString().contains("Tor-native fetch engine"));assertFalse(compact.toString().contains("\u001b"));
        assertEquals("a b c",Brand.safe("a\u001bb\u202ec"));
    }
}
