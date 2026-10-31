package dev.onionall.brawler.tor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.junit.Test;
import static org.junit.Assert.*;

/** Policy tests use generated valid v3 representations, without resolving any hostname. */
public class TorPolicyTest {
    static String onion(int seed) throws Exception {
        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) seed);
        MessageDigest digest = MessageDigest.getInstance("SHA3-256");
        digest.update(".onion checksum".getBytes(StandardCharsets.US_ASCII));
        digest.update(publicKey);
        digest.update((byte) 3);
        byte[] checksum = digest.digest();
        byte[] encoded = Arrays.copyOf(publicKey, 35);
        encoded[32] = checksum[0]; encoded[33] = checksum[1]; encoded[34] = 3;
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder host = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : encoded) {
            buffer = (buffer << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; host.append(alphabet.charAt((buffer >>> bits) & 31)); }
        }
        return host + ".onion";
    }

    static Config configuration(String host, int port) {
        Config config = new Config();
        config.put("tor.mode", "ONLY");
        config.put("tor.socks.host", "127.0.0.1");
        config.put("tor.socks.port", port);
        config.put("tor.allowed.hosts", List.of(host));
        config.put("http.timeout", 1000);
        config.put("http.content.limit", 65536);
        config.put("http.agent.name", "OnionallBrawlerMTwoTest");
        return config;
    }

    @Test public void validV3UrlsNormalizeWithoutDns() throws Exception {
        String host = onion(1);
        TorPolicy policy = new TorPolicy(configuration(host, 9050));
        assertEquals("http://" + host + "/a", policy.normalize("http://" + host + "/a#duplicate"));
        assertEquals("https://" + host + "/b", policy.normalize("https://" + host + "/b"));
        assertEquals("http://" + host + "/a%2Fb?q=a%26b", policy.normalize("http://" + host + "/a%2Fb?q=a%26b#discard"));
    }

    @Test public void malformedOrOutOfScopeUrlsAreRejected() throws Exception {
        String host = onion(1);
        TorPolicy policy = new TorPolicy(configuration(host, 9050));
        for (String url : List.of("http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/",
                "http://[::1]/", "http://example.com/", "file:///etc/passwd", "ftp://" + host + "/",
                "http://short.onion/", "http://" + "a".repeat(56) + ".onion/",
                "http://" + onion(2) + "/", "http://user:password@" + host + "/",
                "http://" + host + ":7071/", "http://" + host + ".evil.invalid/", "not a url")) {
            assertThrows("URL accepted: " + url, Exception.class, () -> policy.normalize(url));
        }
    }

    @Test public void configurationCannotSelectDirectOrIncompleteProxy() throws Exception {
        String host = onion(1);
        Config direct = configuration(host, 9050); direct.put("tor.mode", "DIRECT");
        assertThrows(Exception.class, () -> new TorPolicy(direct));
        Config missing = configuration(host, 9050); missing.remove("tor.socks.host");
        assertThrows(Exception.class, () -> new TorPolicy(missing));
        Config empty = configuration(host, 9050); empty.put("tor.socks.host", " ");
        assertThrows(Exception.class, () -> new TorPolicy(empty));
        for (int port : new int[] {0, -1, 65536}) {
            assertThrows(Exception.class, () -> new TorPolicy(configuration(host, port)));
        }
        Config fractionalPort = configuration(host, 9050); fractionalPort.put("tor.socks.port", 9050.5);
        assertThrows(Exception.class, () -> new TorPolicy(fractionalPort));
        Config onionGateway = configuration(host, 9050); onionGateway.put("tor.socks.host", host);
        assertThrows(Exception.class, () -> new TorPolicy(onionGateway));
        Config onionFqdnGateway = configuration(host, 9050); onionFqdnGateway.put("tor.socks.host", host + ".");
        assertThrows(Exception.class, () -> new TorPolicy(onionFqdnGateway));
        Config invalidHost = configuration("a".repeat(56) + ".onion", 9050);
        assertThrows(Exception.class, () -> new TorPolicy(invalidHost));
    }

    @Test public void allUpstreamProxyOverrideKeysAreRejected() throws Exception {
        TorPolicy policy = new TorPolicy(configuration(onion(1), 9050));
        for (String key : List.of("http.proxy.skip", "http.proxy", "http.proxy.host", "http.proxy.port",
                "http.proxy.type", "http.proxy.user", "http.proxy.pass")) {
            Metadata metadata = new Metadata(); metadata.setValue(key, "true");
            assertThrows("Proxy metadata accepted: " + key, Exception.class, () -> policy.checkMetadata(metadata));
        }
        Metadata harmless = new Metadata(); harmless.setValue("discoveryDate", "2026-10-31T00:00:00Z");
        policy.checkMetadata(harmless);
    }
}
