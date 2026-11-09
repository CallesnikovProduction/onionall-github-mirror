package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Runtime JSON и SOCKS negotiation без обращения к onion-сервисам.
 * @author Стефан Колесников
 * @author GPT-6 Astra
 */
public class RuntimeCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private record Result(int code, JsonNode value) { }
    private Result run(String... arguments) throws Exception {
        var args = new java.util.ArrayList<>(java.util.List.of("--json"));
        args.addAll(java.util.List.of(arguments));
        var output = new StringWriter();
        int code = Onionall.execute(args.toArray(String[]::new), new PrintWriter(output, true),
            new PrintWriter(new StringWriter(), true), false, Map.of(),
            (config, url) -> { throw new AssertionError("Proxy configuration must not fetch onion"); });
        return new Result(code, new ObjectMapper().readTree(output.toString()));
    }
    private Path descriptor(int port) throws Exception {
        Path file = temporary.newFolder().toPath().resolve("runtime.json");
        Files.writeString(file, "{\"socks_host\":\"127.0.0.1\",\"socks_port\":" + port +
            ",\"seconds\":120,\"frontier_host\":\"frontier\",\"private_extra\":\"DO_NOT_PRINT\"}");
        return file;
    }
    @Test public void proxyEditPreservesDescriptorAndOnlyExposesEndpoint() throws Exception {
        Path file = descriptor(9050);
        var changed = run("config", "proxy", "set", "--runtime", file.toString(), "--type", "socks5",
            "--host", "tor", "--port", "9150");
        assertEquals(0, changed.code);
        var stored = new ObjectMapper().readTree(Files.readString(file));
        assertEquals(120, stored.path("seconds").asInt());
        assertEquals("frontier", stored.path("frontier_host").asText());
        assertEquals("DO_NOT_PRINT", stored.path("private_extra").asText());
        var shown = run("config", "proxy", "show", "--runtime", file.toString());
        assertEquals(0, shown.code);
        assertEquals("tor", shown.value.path("host").asText());
        assertEquals(9150, shown.value.path("port").asInt());
        assertFalse(shown.value.toString().contains("DO_NOT_PRINT"));
        assertFalse(shown.value.path("direct_fallback").asBoolean(true));
    }
    @Test public void invalidProxyNeverReplacesExistingConfiguration() throws Exception {
        Path file = descriptor(9050);
        String original = Files.readString(file);
        for (String host : new String[]{"user:password@host", "https://host", "invalid.onion"}) {
            assertNotEquals(0, run("config", "proxy", "set", "--runtime", file.toString(),
                "--host", host, "--port", "9050").code);
            assertEquals(original, Files.readString(file));
        }
        assertNotEquals(0, run("config", "proxy", "set", "--runtime", file.toString(),
            "--type", "direct", "--host", "tor", "--port", "9050").code);
        assertNotEquals(0, run("config", "proxy", "set", "--runtime", file.toString(),
            "--host", "tor", "--port", "0").code);
        assertEquals(original, Files.readString(file));
    }
    @Test public void doctorRequiresRealSocksNegotiationAndDoesNotClaimTor() throws Exception {
        for (boolean accept : new boolean[]{true, false}) {
            try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(5000);
                var response = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (var client = server.accept()) {
                        client.setSoTimeout(5000);
                        assertArrayEquals(new byte[]{5, 1, 0}, client.getInputStream().readNBytes(3));
                        client.getOutputStream().write(new byte[]{5, (byte) (accept ? 0 : 255)});
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                var result = run("system", "doctor", "--runtime", descriptor(server.getLocalPort()).toString());
                response.get(6, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(accept ? 0 : 6, result.code);
                assertEquals("NOT_CHECKED", result.value.path("tor_bootstrap").asText());
                assertEquals(0, result.value.path("onion_requests").asInt(-1));
                assertEquals(accept ? "READY" : "FAILED", result.value.path("socks5_negotiation").asText());
            }
        }
    }
    @Test public void malformedOrOversizedDescriptorHasNoFallback() throws Exception {
        Path file = descriptor(9050);
        for (String content : new String[]{"{bad-json", " ".repeat(32769)}) {
            Files.writeString(file, content);
            assertNotEquals(0, run("config", "proxy", "show", "--runtime", file.toString()).code);
            assertEquals(content, Files.readString(file));
        }
    }
}
