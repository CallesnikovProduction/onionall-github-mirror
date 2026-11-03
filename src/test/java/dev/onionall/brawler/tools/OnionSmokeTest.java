package dev.onionall.brawler.tools;

import dev.onionall.brawler.tor.TorPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class OnionSmokeTest {
    @Test public void fixedTargetIsValidAndOtherTargetsAreRejected() throws Exception {
        TorPolicy policy = new TorPolicy(OnionSmoke.configuration());
        assertEquals(OnionSmoke.URL, policy.normalize(OnionSmoke.URL));
        assertThrows(IOException.class, () -> policy.normalize("http://127.0.0.1/"));
    }
    @Test public void onlySuccessfulHtmlIsSavedAndTitleIsParsed() throws Exception {
        Path output = Files.createTempFile("onion-smoke-test-", ".html");
        try {
            Files.delete(output);
            Metadata md = new Metadata(); md.setValue("content-type", "text/html; charset=UTF-8");
            byte[] html = "<!doctype html><html><title>A &amp; B</title></html>".getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> OnionSmoke.save(new ProtocolResponse(html, 302, md), output));
            assertFalse(Files.exists(output));
            md.setValue("content-type", "application/octet-stream");
            assertThrows(IOException.class, () -> OnionSmoke.save(new ProtocolResponse(html, 200, md), output));
            assertFalse(Files.exists(output));
            md.setValue("content-type", "text/html");
            assertEquals("A & B", OnionSmoke.save(new ProtocolResponse(html, 200, md), output));
            assertArrayEquals(html, Files.readAllBytes(output));
        } finally { Files.deleteIfExists(output); }
    }
}
