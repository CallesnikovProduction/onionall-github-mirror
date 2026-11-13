package dev.onionall.brawler.tor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local SOCKS wire checks validate the client, and never stand in for real Tor acceptance. */
public class TorProtocolTest {
    private static TorProtocol protocol(String host, int port) {
        TorProtocol protocol = new TorProtocol();
        protocol.configure(TorPolicyTest.configuration(host, port));
        return protocol;
    }

    @Test public void optInDiagnosticsExposeBoundedLifecycleWithoutSensitiveUrls() throws Exception {
        String host=TorPolicyTest.onion(4);
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream previous=System.err;
        try(SocksWire wire=new SocksWire(reply(200,"ok"),reply(200,"ok"))) {
            Config conf=TorPolicyTest.configuration(host,wire.port());conf.put("tor.diagnostics.enabled",true);
            TorProtocol protocol=new TorProtocol();
            try {
                System.setErr(new PrintStream(output,true,StandardCharsets.UTF_8));
                protocol.configure(conf);
                protocol.getProtocolOutput("http://"+host+"/private-path?credential=never-log",new Metadata());
                protocol.getProtocolOutput("http://"+host+"/second",new Metadata());
            } finally {protocol.cleanup();System.setErr(previous);}
            String text=output.toString(StandardCharsets.UTF_8);
            assertFalse(text.contains(host));assertFalse(text.contains("private-path"));assertFalse(text.contains("credential"));
            assertTrue(text.contains("socks_connect_start"));assertTrue(text.contains("socks_connect_end"));
            assertTrue(text.contains("\"new_connection\":true"));assertFalse(text.contains("\"new_connection\":false"));
            assertTrue(text.contains("configured"));assertTrue(text.contains("cleanup"));
            for(String line:text.lines().toList()) if(line.startsWith("TOR_TRACE ")) {
                var record=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(10));
                assertTrue(record.has("client"));assertTrue(record.has("timeout_ms"));assertTrue(record.has("event"));
                assertFalse(record.has("thread"));
                assertTrue(java.util.Set.of("okhttp","okio","application").contains(record.path("thread_kind").asText()));
            }
        } finally {System.setErr(previous);}
    }

    @Test public void configuredProxyHostnameIsFrozenAsNumericEndpointForNativeJdk() throws Exception {
        String host=TorPolicyTest.onion(4);
        try(SocksWire wire=new SocksWire(reply(200,"ok"))) {
            Config c=TorPolicyTest.configuration(host,wire.port());c.put("tor.socks.host","localhost");
            TorProtocol p=new TorProtocol();
            try {
                p.configure(c);
                var field=TorProtocol.class.getDeclaredField("client");field.setAccessible(true);
                var client=(okhttp3.OkHttpClient)field.get(p);
                var address=(InetSocketAddress)client.proxy().address();
                assertEquals(address.getAddress().getHostAddress(),address.getHostString());
                assertEquals(200,p.getProtocolOutput("http://"+host+"/",new Metadata()).getStatusCode());
                assertEquals(3,wire.await(1).get(0).type);
            }finally{p.cleanup();}
        }
    }

    @Test public void nativeJdkUserPassNegotiationKeepsRemoteDnsAndFreshConnections() throws Exception {
        String host=TorPolicyTest.onion(4);
        try(SocksWire wire=new SocksWire(true,reply(200,"one"),reply(200,"two"))) {
            TorProtocol p=protocol(host,wire.port());
            try {
                assertEquals(200,p.getProtocolOutput("http://"+host+"/one",new Metadata()).getStatusCode());
                assertEquals(200,p.getProtocolOutput("http://"+host+"/two",new Metadata()).getStatusCode());
                for(Attempt a:wire.await(2)){assertEquals(3,a.type);assertEquals(host,a.host);assertEquals(80,a.port);}
                assertEquals(2,wire.authenticated);wire.assertNoFurtherConnection();
            }finally{p.cleanup();}
        }
    }

    @Test public void diagnosticOutputHasHardPerClientRecordCap() throws Exception {
        String host=TorPolicyTest.onion(4);
        String[] replies=new String[35];Arrays.fill(replies,reply(200,"ok"));
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream previous=System.err;
        try(SocksWire wire=new SocksWire(replies)) {
            Config conf=TorPolicyTest.configuration(host,wire.port());conf.put("tor.diagnostics.enabled",true);
            TorProtocol protocol=new TorProtocol();
            try {
                System.setErr(new PrintStream(output,true,StandardCharsets.UTF_8));protocol.configure(conf);
                for(int i=0;i<35;i++)assertEquals(200,protocol.getProtocolOutput("http://"+host+"/",new Metadata()).getStatusCode());
                wire.await(35);
            } finally {protocol.cleanup();System.setErr(previous);}
            assertEquals(256,output.toString(StandardCharsets.UTF_8).lines().filter(line->line.startsWith("TOR_TRACE ")).count());
        } finally {System.setErr(previous);}
    }

    @Test public void cleanupIsTerminalAndIdempotentWithoutNewSockets() throws Exception {
        String host=TorPolicyTest.onion(4);
        try (SocksWire wire=new SocksWire(reply(200,"ok"))) {
            TorProtocol protocol=protocol(host,wire.port());
            protocol.cleanup(); protocol.cleanup();
            assertEquals("PROTOCOL_CLOSED",assertThrows(IOException.class,()->protocol.getProtocolOutput("http://"+host+"/",new Metadata())).getMessage());
            assertEquals("PROTOCOL_CLOSED",assertThrows(IOException.class,()->protocol.getRobotRules("http://"+host+"/")).getMessage());
            assertThrows(IllegalStateException.class,()->protocol.configure(TorPolicyTest.configuration(host,wire.port())));
            wire.assertNoFurtherConnection();
            TorProtocol fresh=protocol(host,wire.port());
            try { assertEquals(200,fresh.getProtocolOutput("http://"+host+"/",new Metadata()).getStatusCode()); }
            finally { fresh.cleanup(); }
        }
    }

    @Test public void cleanupBeforeConfigureIsTerminal() {
        TorProtocol protocol=new TorProtocol(); protocol.cleanup(); protocol.cleanup();
        assertThrows(IllegalStateException.class,()->protocol.configure(TorPolicyTest.configuration(TorPolicyTest.onion(4),9050)));
    }

    @Test public void cleanupCancelsBlockedSocksCall() throws Exception { checkCleanupCancellation(false); }
    @Test public void cleanupCancelsBlockedRobotsWithoutReportingNetworkOutage() throws Exception { checkCleanupCancellation(true); }
    private void checkCleanupCancellation(boolean robots) throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch accepted=new CountDownLatch(1);
        String host=TorPolicyTest.onion(4);
        try (ServerSocket server=new ServerSocket(0,10,InetAddress.getLoopbackAddress())) {
            TorProtocol protocol=new TorProtocol();
            Config config=TorPolicyTest.configuration(host,server.getLocalPort());config.put("http.timeout",120000);
            protocol.configure(config);
            Future<?> peer=executor.submit(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(3000); accepted.countDown();
                    while(socket.getInputStream().read()!=-1) { }
                } catch(IOException ignored) { }
            });
            try {
                Future<IOException> request=executor.submit(()->assertThrows(IOException.class,()->{
                    if(robots) protocol.getRobotRules("http://"+host+"/");
                    else protocol.getProtocolOutput("http://"+host+"/",new Metadata());
                }));
                assertTrue(accepted.await(2,TimeUnit.SECONDS));
                protocol.cleanup();
                assertEquals("PROTOCOL_CLOSED",request.get(2,TimeUnit.SECONDS).getMessage());
                peer.get(4,TimeUnit.SECONDS);
            } finally { protocol.cleanup(); }
        } finally { executor.shutdownNow(); }
    }

    @Test public void robotsTransportFailureRetainsCauseAndNeverCachesAllowAll() throws Exception {
        String host=TorPolicyTest.onion(4);
        try(SocksWire wire=new SocksWire("",reply(200,"User-agent: *\nDisallow: /blocked\n"))) {
            TorProtocol protocol=protocol(host,wire.port());
            try {
                IOException error=assertThrows(IOException.class,()->protocol.getRobotRules("http://"+host+"/"));
                assertEquals("ROBOTS_UNAVAILABLE",error.getMessage());
                assertTrue(error.getCause() instanceof IOException);
                assertEquals("ONION_UNREACHABLE",error.getCause().getMessage());
                var recovered=protocol.getRobotRules("http://"+host+"/");
                assertTrue(recovered.isAllowed("http://"+host+"/a"));
                assertFalse(recovered.isAllowed("http://"+host+"/blocked"));
                assertEquals(2,wire.await(2).size());
            } finally {protocol.cleanup();}
        }
    }

    @Test public void encodedDotPathIsRejectedBeforeRobotsOrTransport() throws Exception {
        String host=TorPolicyTest.onion(4);
        try(SocksWire wire=new SocksWire(reply(200,"unexpected"))) {
            TorProtocol protocol=protocol(host,wire.port());
            try {
                for(String path:List.of("/safe/%2e%2e/blocked","/safe/.%2E/blocked","/../blocked","/safe/../blocked","//blocked","/./blocked")) {
                    assertEquals("SCOPE_REJECTED",assertThrows(IOException.class,()->protocol.getProtocolOutput("http://"+host+path,new Metadata())).getMessage());
                    assertEquals("SCOPE_REJECTED",assertThrows(IOException.class,()->protocol.getRobotRules("http://"+host+path)).getMessage());
                }
                wire.assertNoFurtherConnection();
            } finally {protocol.cleanup();}
        }
    }

    @Test public void discoveryMimeRejectsHeadersBeforeWaitingForBody()throws Exception {
        String host=TorPolicyTest.onion(4);
        for(String headers:List.of("Content-Type: application/zip\r\n","Content-Type: text/html; broken\r\n","Content-Type: text/html\r\nContent-Encoding: gzip\r\n","","Content-Type: text/html\r\nContent-Type: application/zip\r\n")) {
            try(SocksWire wire=new SocksWire("HTTP/1.1 200 OK\r\n"+headers+"Content-Length: 999999999\r\nConnection: close\r\n\r\n")) {
                TorProtocol p=new TorProtocol();Config c=TorPolicyTest.configuration(host,wire.port());c.put("tor.discovery.html.only",true);p.configure(c);
                try {assertEquals("METADATA_ONLY",assertThrows(IOException.class,()->p.getProtocolOutput("http://"+host+"/download",new Metadata())).getMessage());}
                finally {p.cleanup();}
                wire.await(1);
            }
        }
    }
    @Test public void discoveryMimeBoundsChunkedAndSkipsRedirectPayload()throws Exception {
        String host=TorPolicyTest.onion(4),body="<html>"+"x".repeat(128)+"</html>";
        String chunked="HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"+Integer.toHexString(body.length())+"\r\n"+body+"\r\n0\r\n\r\n";
        String redirect="HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Type: application/zip\r\nContent-Length: 999999999\r\nConnection: close\r\n\r\n";
        try(SocksWire wire=new SocksWire(chunked,redirect)) {
            TorProtocol p=new TorProtocol();Config c=TorPolicyTest.configuration(host,wire.port());c.put("tor.discovery.html.only",true);c.put("http.content.limit",32);p.configure(c);
            try {
                assertEquals("BODY_LIMIT_EXCEEDED",assertThrows(IOException.class,()->p.getProtocolOutput("http://"+host+"/large",new Metadata())).getMessage());
                var r=p.getProtocolOutput("http://"+host+"/redirect",new Metadata());assertEquals(302,r.getStatusCode());assertEquals(0,r.getContent().length);assertEquals("http://"+host+"/next",r.getMetadata().getFirstValue("location"));
                wire.await(2);wire.assertNoFurtherConnection();
            } finally {p.cleanup();}
        }
    }

    @Test public void discoveryMimeHtmlRobotsAndLyingTypeAreBounded()throws Exception {
        String host=TorPolicyTest.onion(4);
        try(SocksWire wire=new SocksWire(reply(200,"<html><body>ok</body></html>").replace("text/plain","text/html"),reply(200,"PK-binary").replace("text/plain","text/html"),"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 14\r\nConnection: close\r\n\r\nUser-agent: *\n")) {
            TorProtocol p=new TorProtocol();Config c=TorPolicyTest.configuration(host,wire.port());c.put("tor.discovery.html.only",true);p.configure(c);
            try {
                assertEquals(200,p.getProtocolOutput("http://"+host+"/",new Metadata()).getStatusCode());
                assertEquals("METADATA_ONLY",assertThrows(IOException.class,()->p.getProtocolOutput("http://"+host+"/lying",new Metadata())).getMessage());
                assertNotNull(p.getRobotRules("http://"+host+"/"));
            } finally {p.cleanup();}
        }
    }

    @Test public void unsafeHttpConfigurationFailsBeforeStartingTransport() throws Exception {
        String host = TorPolicyTest.onion(3);
        for (String key : List.of("http.robots.file.skip", "http.allow.redirects", "http.trust.everything")) {
            TorProtocol protocol = new TorProtocol();
            Config config = TorPolicyTest.configuration(host, 9050); config.put(key, true);
            try { assertThrows("Unsafe config accepted: " + key, Exception.class, () -> protocol.configure(config)); }
            finally { protocol.cleanup(); }
        }
    }

    @Test public void socksCarriesUnresolvedOnionNameAndReturnsHttp200And404() throws Exception {
        String host = TorPolicyTest.onion(4);
        try (SocksWire wire = new SocksWire(reply(200, "document"), reply(404, "missing"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                ProtocolResponse response = protocol.getProtocolOutput("http://" + host + "/a", new Metadata());
                assertEquals(200, response.getStatusCode());
                assertArrayEquals("document".getBytes(StandardCharsets.UTF_8), response.getContent());
                assertEquals(404, protocol.getProtocolOutput("http://" + host + "/missing", new Metadata()).getStatusCode());
                List<Attempt> attempts = wire.await(2);
                for (Attempt attempt : attempts) {
                    assertEquals("Destination must be SOCKS ATYP=DOMAIN, not local DNS", 3, attempt.type);
                    assertEquals(host, attempt.host); assertEquals(80, attempt.port);
                }
                assertTrue(attempts.get(0).request.startsWith("GET /a HTTP/1.1"));
                assertTrue(attempts.get(1).request.startsWith("GET /missing HTTP/1.1"));
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void redirectsAreReturnedToCrawlerRatherThanAutomaticallyFollowed() throws Exception {
        String host = TorPolicyTest.onion(5);
        try (SocksWire wire = new SocksWire(redirect("/a"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                ProtocolResponse response = protocol.getProtocolOutput("http://" + host + "/dir/start", new Metadata());
                assertEquals(302, response.getStatusCode());
                assertArrayEquals(new String[]{"http://" + host + "/a"}, response.getMetadata().getValues("location"));
                assertEquals(1, wire.await(1).size());
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void queryOnlyRedirectRetainsDocumentPathAndRawQueryWithoutFollowing() throws Exception {
        String host = TorPolicyTest.onion(5);
        String query = "?page=2&id=123&label=a%26b&empty=#discard";
        try (SocksWire wire = new SocksWire(redirect(query))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                ProtocolResponse response = protocol.getProtocolOutput(
                        "http://" + host + "/forum/thread?id=123&page=1", new Metadata());
                assertEquals(302, response.getStatusCode());
                assertArrayEquals(new String[]{"http://" + host + "/forum/thread?page=2&id=123&label=a%26b&empty="},
                        response.getMetadata().getValues("location"));
                var attempts = wire.await(1);
                assertEquals(3, attempts.get(0).type);
                assertEquals(host, attempts.get(0).host);
                assertTrue(attempts.get(0).request.startsWith("GET /forum/thread?id=123&page=1 HTTP/1.1"));
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void ambiguousMissingAndBlankRedirectLocationsArePolicyFailures() throws Exception {
        String host = TorPolicyTest.onion(5);
        for (String headers : List.of("Location: /safe\r\nLocation: http://example.com/\r\n",
                "Location: http://example.com/\r\nlOcAtIoN: /safe\r\n",
                "Location: /safe\r\nLocation: /safe\r\n", "", "Location:   \r\n")) {
            try (SocksWire wire = new SocksWire("HTTP/1.1 302 Found\r\n" + headers
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    IOException error = assertThrows(IOException.class,
                            () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
                    assertEquals("SCOPE_REJECTED", error.getMessage());
                    wire.await(1); wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void relativeRedirectIsCanonicalizedAndNotModifiedNeedsNoLocation() throws Exception {
        String host = TorPolicyTest.onion(5);
        try (SocksWire wire = new SocksWire(redirect("../next#discard"), reply(304, ""),
                "HTTP/1.1 304 Not Modified\r\nLocation: http://example.com/\r\nConnection: close\r\n\r\n")) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                ProtocolResponse redirect = protocol.getProtocolOutput("http://" + host + "/dir/start", new Metadata());
                assertArrayEquals(new String[]{"http://" + host + "/next"}, redirect.getMetadata().getValues("location"));
                for (int index = 0; index < 2; index++) {
                    ProtocolResponse unchanged = protocol.getProtocolOutput("http://" + host + "/", new Metadata());
                    assertEquals(304, unchanged.getStatusCode());
                    assertNull(unchanged.getMetadata().getFirstValue("location"));
                }
                wire.await(3); wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void metadataPrefixIsMandatoryAndRemoteHeadersCannotOverwriteControlKeys() throws Exception {
        String host = TorPolicyTest.onion(5);
        for (Object prefix : Arrays.asList(null, "", "  ", 1)) {
            Config config = TorPolicyTest.configuration(host, 9050);
            if (prefix == null) config.remove("protocol.md.prefix");
            else config.put("protocol.md.prefix", prefix);
            TorProtocol protocol = new TorProtocol();
            try { assertThrows(IllegalArgumentException.class, () -> protocol.configure(config)); }
            finally { protocol.cleanup(); }
        }
        String[] keys = {"scheduler.delay.mins", "fetch.error.count", "status.store.as.is.with.nextfetchdate", "isSitemap", "isFeed"};
        StringBuilder headers = new StringBuilder();
        for (String key : keys) headers.append(key).append(": hostile\r\n");
        try (SocksWire wire = new SocksWire("HTTP/1.1 200 OK\r\n" + headers
                + "Content-Length: 0\r\nConnection: close\r\n\r\n")) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                Metadata response = protocol.getProtocolOutput("http://" + host + "/", new Metadata()).getMetadata();
                Metadata combined = new Metadata();
                for (String key : keys) combined.setValue(key, "trusted");
                // The same Metadata operation used by SimpleFetcherBolt 3.7.0.
                combined.putAll(response, (String) TorPolicyTest.configuration(host, wire.port()).get("protocol.md.prefix"));
                for (String key : keys) {
                    assertEquals("trusted", combined.getFirstValue(key));
                    assertEquals("hostile", combined.getFirstValue("protocol." + key));
                    assertNull(response.getFirstValue("protocol." + key));
                }
                wire.await(1); wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void htmlRobotsIsRejectedInsteadOfCachedAsAllowAll() throws Exception {
        String host = TorPolicyTest.onion(9);
        for (String response : List.of(reply(200, "<!DOCTYPE html><html><body>error</body></html>"),
                reply(200, "<!-- unavailable -->\n<HTML lang='en'><body>error</body></HTML>"),
                reply(200, "<?xml version='1.0'?>\n<!-- unavailable --><html><body>error</body></html>"),
                reply(200, "<!-- preamble -->\n".repeat(2000) + "<html>error</html>"),
                reply(200, "error").replace("text/plain", "text/html; charset=UTF-8"),
                reply(200, "error").replace("text/plain", "application/xhtml+xml"))) {
            try (SocksWire wire = new SocksWire(response, reply(200, "User-agent: *\nDisallow: /blocked\n"))) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    assertEquals("ROBOTS_UNAVAILABLE", assertThrows(IOException.class,
                            () -> protocol.getRobotRules("http://" + host + "/")).getMessage());
                    assertFalse(protocol.getRobotRules("http://" + host + "/blocked").isAllowed("http://" + host + "/blocked"));
                    wire.await(2); wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void emptyUnmatchedWildcardAndSpecificRobotsKeepParserSemantics() throws Exception {
        String host = TorPolicyTest.onion(9), base = "http://" + host;
        for (String body : List.of("", "# <html> is a comment, not a document\n", "User-agent: AnotherCrawler\nDisallow: /\n",
                "User-agent: *\nDisallow: /blocked\nAllow: /blocked/open\n",
                "User-agent: OnionallBrawlerMTwoTest\nDisallow: /blocked\nAllow: /blocked/open\n")) {
            try (SocksWire wire = new SocksWire(reply(200, body))) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    var rules = protocol.getRobotRules(base + "/");
                    assertTrue(rules.isAllowed(base + "/blocked/open"));
                    assertEquals(!body.contains("Allow:"), rules.isAllowed(base + "/blocked"));
                    assertTrue(rules.isAllowed(base + "/ordinary"));
                    protocol.getRobotRules(base + "/other"); // accepted rules are cached
                    wire.await(1); wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void oversizedAndNon200RobotsAreControlledFailures() throws Exception {
        String host = TorPolicyTest.onion(9);
        for (String response : List.of(reply(200, "x".repeat(65537)), reply(404, ""), reply(503, ""),
                reply(304, ""), redirect("/robots.txt"))) {
            try (SocksWire wire = new SocksWire(response)) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    assertEquals("ROBOTS_UNAVAILABLE", assertThrows(IOException.class,
                            () -> protocol.getRobotRules("http://" + host + "/")).getMessage());
                    wire.await(1); wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void robotsBomIsSupportedAndMalformedEncodingIsRejected() throws Exception {
        String host = TorPolicyTest.onion(9), base = "http://" + host;
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            for (boolean html : List.of(false, true)) {
                String body = "\uFEFF" + (html ? "<html lang='en'><body>error</body></html>"
                        : "User-agent: *\nDisallow: /blocked\n");
                try (SocksWire wire = new SocksWire(new byte[][]{replyBytes(body.getBytes(charset))})) {
                    TorProtocol protocol = protocol(host, wire.port());
                    try {
                        if (html) assertEquals("ROBOTS_UNAVAILABLE", assertThrows(IOException.class,
                                () -> protocol.getRobotRules(base + "/")).getMessage());
                        else assertFalse(protocol.getRobotRules(base + "/").isAllowed(base + "/blocked"));
                        wire.await(1); wire.assertNoFurtherConnection();
                    } finally { protocol.cleanup(); }
                }
            }
        }
        byte[] malformed = "# ?\nUser-agent: *\nDisallow: /blocked\n".getBytes(StandardCharsets.UTF_8);
        malformed[2] = (byte) 0xc3; // incomplete UTF-8 sequence followed by LF
        assertTrue(new String(malformed, StandardCharsets.UTF_8).contains("\uFFFD"));
        try (SocksWire wire = new SocksWire(new byte[][]{replyBytes(malformed)})) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                // Application policy rejects corruption before the replacement-decoding parser.
                assertEquals("ROBOTS_UNAVAILABLE", assertThrows(IOException.class,
                        () -> protocol.getRobotRules(base + "/")).getMessage());
                wire.await(1); wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void corruptedRobotsDirectiveIsNotCachedAndValidRetryIsParsed() throws Exception {
        String host = TorPolicyTest.onion(9), base = "http://" + host;
        for (byte[] malformed : List.of(
                "User-agent: *\nDisallow: /blocked\uD800".getBytes(StandardCharsets.UTF_16),
                new byte[]{(byte)0xff, (byte)0xfe, 65},
                new byte[]{'U', 's', 'e', 'r', '-', 'a', 'g', 'e', 'n', 't', ':', ' ', (byte)0xc3})) {
            // A Java encoder replaces an unpaired surrogate; use truncated bytes for UTF-16.
            if (malformed.length > 20) malformed = java.util.Arrays.copyOf(malformed, malformed.length - 1);
            try (SocksWire wire = new SocksWire(new byte[][]{replyBytes(malformed),
                    replyBytes("User-agent: *\nDisallow: /blocked\n".getBytes(StandardCharsets.UTF_8))})) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    assertEquals("ROBOTS_UNAVAILABLE", assertThrows(IOException.class,
                            () -> protocol.getRobotRules(base + "/")).getMessage());
                    assertFalse(protocol.getRobotRules(base + "/").isAllowed(base + "/blocked"));
                    wire.await(2); wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void outOfScopeRedirectNeverConnectsToTarget() throws Exception {
        String host = TorPolicyTest.onion(6);
        try (ServerSocket directTarget = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocksWire wire = new SocksWire(redirect("http://127.0.0.1:" + directTarget.getLocalPort() + "/"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
                wire.await(1);
                directTarget.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, directTarget::accept);
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void outOfScopeInitialUrlFailsBeforeAnySocketConnect() throws Exception {
        String host = TorPolicyTest.onion(6);
        try (ServerSocket directTarget = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocksWire wire = new SocksWire()) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                for (String url : List.of("http://127.0.0.1:" + directTarget.getLocalPort() + "/",
                        "http://" + TorPolicyTest.onion(16) + "/", "http://outside.invalid/",
                        "http://" + host + ":7071/", "file:///etc/passwd")) {
                    assertEquals("SCOPE_REJECTED", assertThrows(IOException.class,
                            () -> protocol.getProtocolOutput(url, new Metadata())).getMessage());
                }
                directTarget.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, directTarget::accept);
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void malformedRedirectHasControlledScopeFailureAndNoFollow() throws Exception {
        String host = TorPolicyTest.onion(13);
        try (SocksWire wire = new SocksWire(redirect("http://[malformed"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                Exception failure = assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
                assertEquals("SCOPE_REJECTED", failure.getMessage());
                wire.await(1); wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void metadataBypassFailsBeforeAnySocketConnect() throws Exception {
        String host = TorPolicyTest.onion(7);
        try (SocksWire wire = new SocksWire()) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                for (String key : List.of("http.proxy.skip", "http.proxy", "http.proxy.host", "http.proxy.type")) {
                    Metadata metadata = new Metadata(); metadata.setValue(key, "true");
                    Exception failure = assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", metadata));
                    assertEquals("PROXY_POLICY_VIOLATION", failure.getMessage());
                }
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void refusedProxyNeverConsultsSystemDirectProxySelector() throws Exception {
        String host = TorPolicyTest.onion(8);
        int refusedPort;
        try (ServerSocket socket = new ServerSocket(0)) { refusedPort = socket.getLocalPort(); }
        ProxySelector original = ProxySelector.getDefault();
        List<URI> selections = new ArrayList<>();
        ProxySelector.setDefault(new ProxySelector() {
            public List<Proxy> select(URI uri) { selections.add(uri); return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress sa, IOException error) { }
        });
        TorProtocol protocol = protocol(host, refusedPort);
        try {
            assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
            assertTrue("Explicit SOCKS must never consult system DIRECT selector", selections.isEmpty());
        } finally { protocol.cleanup(); ProxySelector.setDefault(original); }
    }

    @Test public void robotsFetchedThroughSameSocksAndDisallowIsEnforced() throws Exception {
        String host = TorPolicyTest.onion(9);
        try (SocksWire wire = new SocksWire(reply(200, "User-agent: *\nDisallow: /blocked\nCrawl-delay: 2\n"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                assertFalse(protocol.getRobotRules("http://" + host + "/blocked").isAllowed("http://" + host + "/blocked"));
                Attempt attempt = wire.await(1).get(0);
                assertEquals(3, attempt.type); assertEquals(host, attempt.host);
                assertTrue(attempt.request.startsWith("GET /robots.txt HTTP/1.1"));
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void unavailableRobotsNeverReturnsAllowAll() throws Exception {
        String host = TorPolicyTest.onion(10);
        try (SocksWire wire = new SocksWire(reply(503, "unavailable"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                try {
                    assertFalse("Unavailable robots must deny", protocol.getRobotRules("http://" + host + "/").isAllowed("http://" + host + "/"));
                } catch (Exception expected) {
                    // Either a controlled failure or deny rules preserve the invariant.
                }
                assertTrue(wire.await(1).get(0).request.startsWith("GET /robots.txt HTTP/1.1"));
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void timeoutAndResetStayOnSocksAndDoNotRetry() throws Exception {
        String host = TorPolicyTest.onion(11);
        for (String failure : List.of("__STALL__", "__RESET__")) {
            try (SocksWire wire = new SocksWire(failure)) {
                TorProtocol protocol = protocol(host, wire.port());
                try {
                    long started = System.nanoTime();
                    Exception error = assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
                    assertEquals("__STALL__".equals(failure) ? "REQUEST_TIMEOUT" : "ONION_UNREACHABLE", error.getMessage());
                    assertTrue("Call must finish within bounded timeout", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
                    assertEquals(host, wire.await(1).get(0).host);
                    wire.assertNoFurtherConnection();
                } finally { protocol.cleanup(); }
            }
        }
    }

    @Test public void responseBodyLimitCannotBeOverriddenByMetadata() throws Exception {
        String host = TorPolicyTest.onion(12);
        try (SocksWire wire = new SocksWire(reply(200, "x".repeat(100)))) {
            TorProtocol protocol = new TorProtocol();
            Config config = TorPolicyTest.configuration(host, wire.port());
            config.put("http.content.limit", 16);
            protocol.configure(config);
            try {
                assertThrows(Exception.class, () -> protocol.getProtocolOutput("http://" + host + "/", new Metadata()));
                assertEquals(host, wire.await(1).get(0).host);
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void http10ImplicitCloseUsesNewSocksConnectionAfterRobotsWithoutRetry() throws Exception {
        String host = TorPolicyTest.onion(14);
        try (SocksWire wire = new SocksWire(http10Robots(""), http10Document(""))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                assertTrue(protocol.getRobotRules("http://" + host + "/").isAllowed("http://" + host + "/"));
                ProtocolResponse response = protocol.getProtocolOutput("http://" + host + "/", new Metadata());
                assertEquals(200, response.getStatusCode());
                assertArrayEquals("document".getBytes(StandardCharsets.UTF_8), response.getContent());
                List<Attempt> attempts = wire.await(2);
                assertTrue(attempts.get(0).request.startsWith("GET /robots.txt HTTP/1.1"));
                assertTrue(attempts.get(1).request.startsWith("GET / HTTP/1.1"));
                for (Attempt attempt : attempts) assertEquals(host, attempt.host);
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    @Test public void http10ExplicitCloseUsesNewSocksConnectionAfterRobots() throws Exception {
        String host = TorPolicyTest.onion(14);
        try (SocksWire wire = new SocksWire(http10Robots("Connection: close\r\n"),
                                          http10Document("Connection: close\r\n"))) {
            TorProtocol protocol = protocol(host, wire.port());
            try {
                assertTrue(protocol.getRobotRules("http://" + host + "/").isAllowed("http://" + host + "/"));
                ProtocolResponse response = protocol.getProtocolOutput("http://" + host + "/", new Metadata());
                assertEquals(200, response.getStatusCode());
                assertArrayEquals("document".getBytes(StandardCharsets.UTF_8), response.getContent());
                List<Attempt> attempts = wire.await(2);
                assertTrue(attempts.get(0).request.startsWith("GET /robots.txt HTTP/1.1"));
                assertTrue(attempts.get(1).request.startsWith("GET / HTTP/1.1"));
                for (Attempt attempt : attempts) assertEquals(host, attempt.host);
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
        }
    }

    private static String http10Robots(String connectionHeader) {
        String robots = "User-agent: *\nDisallow: /blocked\nCrawl-delay: 2\n";
        return "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                + robots.getBytes(StandardCharsets.UTF_8).length + "\r\n" + connectionHeader + "\r\n" + robots;
    }

    private static String http10Document(String connectionHeader) {
        return "HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: 8\r\n"
                + connectionHeader + "\r\n" + "document";
    }

    private static String reply(int status, String body) {
        return "HTTP/1.1 " + status + " Test\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
    }
    private static String redirect(String location) {
        return "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }
    private static byte[] replyBytes(byte[] body) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        response.write(body);
        return response.toByteArray();
    }
    private static final class Attempt {
        int type, port; String host, request;
    }
    private static final class SocksWire implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final BlockingQueue<Attempt> attempts = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        final List<Attempt> observed = new ArrayList<>();
        volatile boolean closed;
        final byte[][] replies;
        final boolean userPass;
        volatile int authenticated;
        SocksWire(String... replies) throws IOException {
            this(Arrays.stream(replies).map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
        }
        SocksWire(boolean userPass,String... replies) throws IOException {
            this(Arrays.stream(replies).map(v->v.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new),userPass);
        }
        SocksWire(byte[][] replies) throws IOException { this(replies,false); }
        SocksWire(byte[][] replies,boolean userPass) throws IOException {
            this.userPass=userPass;
            this.replies = replies;
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            server.setSoTimeout(100);
            executor.submit(this::serve);
        }
        int port() { return server.getLocalPort(); }
        void serve() {
            int index = 0;
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    if (in.readUnsignedByte() != 5) throw new IOException("Not SOCKS5 greeting");
                    byte[] methods=in.readNBytes(in.readUnsignedByte());
                    if(userPass && !java.util.stream.IntStream.range(0,methods.length).anyMatch(i->methods[i]==2))
                        throw new IOException("USER_PASSW not offered");
                    out.write(new byte[]{5,(byte)(userPass?2:0)});out.flush();
                    if(userPass) {
                        if(in.readUnsignedByte()!=1)throw new IOException("Invalid auth version");
                        in.readNBytes(in.readUnsignedByte()); // Discard, never record credentials.
                        in.readNBytes(in.readUnsignedByte());
                        out.write(new byte[]{1,0});out.flush();authenticated++;
                    }
                    if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0)
                        throw new IOException("Not SOCKS5 connect");
                    Attempt attempt = new Attempt(); attempt.type = in.readUnsignedByte();
                    if (attempt.type == 3) attempt.host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                    else if (attempt.type == 1) attempt.host = InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                    else throw new IOException("Unexpected SOCKS address type " + attempt.type);
                    attempt.port = in.readUnsignedShort();
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,80}); out.flush();
                    ByteArrayOutputStream request = new ByteArrayOutputStream();
                    int count = 0;
                    while (request.size() < 16384) {
                        int value = in.read(); if (value < 0) throw new EOFException(); request.write(value);
                        count = (count == 0 || count == 2) ? (value == '\r' ? count + 1 : 0) : (value == '\n' ? count + 1 : 0);
                        if (count == 4) break;
                    }
                    attempt.request = request.toString(StandardCharsets.US_ASCII); attempts.add(attempt);
                    if (index >= replies.length) throw new IOException("Unexpected extra connection");
                    byte[] response = replies[index++];
                    if (Arrays.equals("__RESET__".getBytes(StandardCharsets.US_ASCII), response)) { socket.setSoLinger(true, 0); continue; }
                    if (Arrays.equals("__STALL__".getBytes(StandardCharsets.US_ASCII), response)) { Thread.sleep(1800); continue; }
                    out.write(response); out.flush();
                } catch (SocketTimeoutException timeout) {
                    // Poll only to allow bounded teardown.
                } catch (Throwable failure) { if (!closed) failures.add(failure); }
            }
        }
        List<Attempt> await(int total) throws Exception {
            while (observed.size() < total) {
                Attempt attempt = attempts.poll(4, TimeUnit.SECONDS);
                if (attempt == null) {
                    Throwable failure = failures.poll();
                    throw new AssertionError("No expected SOCKS request; server failure=" + failure);
                }
                observed.add(attempt);
            }
            assertTrue("SOCKS server failed: " + failures, failures.isEmpty());
            return observed;
        }
        void assertNoFurtherConnection() throws Exception {
            assertNull("Unexpected extra HTTP connection", attempts.poll(250, TimeUnit.MILLISECONDS));
            assertTrue("Unexpected SOCKS connection/failure " + failures, failures.isEmpty());
        }
        public void close() throws Exception {
            closed = true; server.close(); executor.shutdownNow();
            if (!executor.awaitTermination(4, TimeUnit.SECONDS)) throw new AssertionError("SOCKS fixture thread leaked");
        }
    }
}
