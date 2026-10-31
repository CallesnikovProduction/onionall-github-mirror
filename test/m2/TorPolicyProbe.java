package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorProtocol;
import java.nio.file.*;
import java.util.*;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;

/** Test-only real protocol calls: no alternate transport or fake successful Tor. */
public final class TorPolicyProbe {
    public static void main(String[] args) throws Exception {
        String host = Files.readString(Path.of("/evidence/onion-hostname.txt")).trim();
        Config config = new Config();
        config.put("tor.mode", "ONLY");
        config.put("tor.socks.host", "tor");
        config.put("tor.socks.port", 9050);
        config.put("tor.allowed.hosts", List.of(host));
        config.put("http.timeout", 10000);
        config.put("http.content.limit", 65536);
        config.put("http.agent.name", "OnionallBrawlerMTwoTest");
        TorProtocol protocol = new TorProtocol();
        protocol.configure(config);
        try {
        List<Map<String,Object>> results = new ArrayList<>();
        for (String key : List.of("http.proxy.skip", "http.proxy.host", "http.proxy.port", "http.proxy.type")) {
            Metadata metadata = new Metadata();
            metadata.setValue(key, key.endsWith("skip") ? "true" : "fixture");
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("metadata_key", key);
            try {
                protocol.getProtocolOutput("http://" + host + "/health", metadata);
                result.put("blocked", false);
            } catch (Exception error) {
                result.put("blocked", error.getMessage().contains("PROXY_POLICY_VIOLATION"));
                result.put("category", error.getMessage().split(":")[0]);
            }
            results.add(result);
        }
        boolean passed = results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("blocked")));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("/evidence/policy.json").toFile(), Map.of("passed", passed, "checks", results));
        if (!passed) throw new IllegalStateException("Proxy metadata bypass rejection failed");
        } finally { protocol.cleanup(); }
    }
}
