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
                assertEquals(302, protocol.getProtocolOutput("http://" + host + "/", new Metadata()).getStatusCode());
                assertEquals(1, wire.await(1).size());
                wire.assertNoFurtherConnection();
            } finally { protocol.cleanup(); }
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
        final String[] replies;
        SocksWire(String... replies) throws IOException {
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
                    in.readNBytes(in.readUnsignedByte()); out.write(new byte[]{5,0}); out.flush();
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
                    String response = replies[index++];
                    if ("__RESET__".equals(response)) { socket.setSoLinger(true, 0); continue; }
                    if ("__STALL__".equals(response)) { Thread.sleep(1800); continue; }
                    out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush();
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
