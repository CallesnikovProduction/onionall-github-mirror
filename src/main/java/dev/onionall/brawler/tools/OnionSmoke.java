package dev.onionall.brawler.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorProtocol;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import org.jsoup.Jsoup;

/** One explicit transport diagnostic, not a crawler or robots acceptance test. */
public final class OnionSmoke {
    public static final String HOST = "juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion";
    public static final String URL = "http://" + HOST + "/";

    static Config configuration() {
        Config c = new Config();
        c.put("tor.mode", "ONLY");
        c.put("tor.socks.host", "127.0.0.1");
        c.put("tor.socks.port", 19050);
        c.put("tor.allowed.hosts", List.of(HOST));
        c.put("protocol.md.prefix", "protocol.");
        c.put("http.agent.name", "OnionallBrawlerSmoke");
        c.put("http.timeout", 30000);
        c.put("http.content.limit", 131072);
        c.put("tor.discovery.html.only", true);
        return c;
    }

    static String save(ProtocolResponse response, Path output) throws IOException {
        if (response.getStatusCode() != 200) throw new IOException("EXPECTED_HTTP_200");
        String type = response.getMetadata().getFirstValue("content-type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("text/html"))
            throw new IOException("EXPECTED_HTML");
        if (response.getContent().length == 0 || response.getContent().length > 131072)
            throw new IOException("INVALID_BODY_SIZE");
        String title = Jsoup.parse(new ByteArrayInputStream(response.getContent()), null, URL).title();
        Files.write(output, response.getContent());
        return title;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This smoke accepts no target overrides");
        Path directory = Path.of(".runtime/downloads").toAbsolutePath();
        Files.createDirectories(directory);
        Path output = directory.resolve("ahmia.html");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", false);
        result.put("url", URL);
        result.put("socks_endpoint", "127.0.0.1:19050");
        result.put("started_at", Instant.now().toString());
        result.put("html_path", null);
        result.put("scope", "single TorProtocol GET; no crawl, robots or system-wide DNS-leak proof");
        long start = System.nanoTime();
        TorProtocol protocol = new TorProtocol();
        Exception failure = null;
        try {
            protocol.configure(configuration());
            ProtocolResponse response = protocol.getProtocolOutput(URL, new Metadata());
            result.put("http_status", response.getStatusCode());
            result.put("bytes", response.getContent().length);
            result.put("title", save(response, output));
            result.put("html_path", output.toString());
            result.put("passed", true);
        } catch (Exception e) {
            failure = e;
            List<String> causes = new ArrayList<>();
            for (Throwable t = e; t != null; t = t.getCause())
                causes.add(t.getClass().getName() + ": " + t.getMessage());
            result.put("errors", causes);
        } finally {
            result.put("elapsed_seconds", (System.nanoTime() - start) / 1e9);
            protocol.cleanup();
            ObjectMapper json = new ObjectMapper();
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("ahmia-smoke.json").toFile(), result);
            System.out.println(json.writeValueAsString(result));
        }
        if (failure != null) throw failure;
    }
}
