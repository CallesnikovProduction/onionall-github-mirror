package dev.onionall.brawler.tor;

import crawlercommons.robots.BaseRobotRules;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.storm.Config;
import org.junit.Test;
import static org.junit.Assert.*;

/** Loopback SOCKS fixture only: independent origins, load ownership and robots cache. */
public class TorRobotsCacheTest {
    private static String url(String host) {return "http://"+host+"/allowed";}
    private static Config config(int port,String... hosts) {
        Config c=TorPolicyTest.configuration(hosts[0],port);
        c.put("tor.allowed.hosts",List.of(hosts));c.put("http.timeout",5000);
        return c;
    }
    @Test public void blockedOriginDoesNotBlockAnotherApprovedOrigin() throws Exception {
        String slow=TorPolicyTest.onion(1),fast=TorPolicyTest.onion(2);
        ExecutorService callers=Executors.newFixedThreadPool(2);
        try(Wire wire=new Wire(slow)) {
            TorProtocol protocol=new TorProtocol();protocol.configure(config(wire.port(),slow,fast));
            try {
                Future<BaseRobotRules> first=callers.submit(()->protocol.getRobotRules(url(slow)));
                assertTrue("Slow request did not enter fixture",wire.blocked.await(2,TimeUnit.SECONDS));
                Future<BaseRobotRules> second=callers.submit(()->protocol.getRobotRules(url(fast)));
                // The slow host remains blocked throughout this assertion.
                assertTrue("Independent host rules denied",second.get(1,TimeUnit.SECONDS).isAllowed(url(fast)));
                assertFalse(first.isDone());
                wire.release.countDown();assertNotNull(first.get(2,TimeUnit.SECONDS));
            } finally {wire.release.countDown();protocol.cleanup();}
        } finally {callers.shutdownNow();assertTrue(callers.awaitTermination(2,TimeUnit.SECONDS));}
    }

    @Test public void oneOriginConcurrentMissIsLoadedOnce() throws Exception {
        String host=TorPolicyTest.onion(1);ExecutorService callers=Executors.newFixedThreadPool(2);
        try(Wire wire=new Wire(host)) {
            TorProtocol protocol=new TorProtocol();protocol.configure(config(wire.port(),host));
            try {
                Future<BaseRobotRules> first=callers.submit(()->protocol.getRobotRules(url(host)));
                assertTrue(wire.blocked.await(2,TimeUnit.SECONDS));
                CountDownLatch secondStarted=new CountDownLatch(1);
                Future<BaseRobotRules> second=callers.submit(()->{secondStarted.countDown();return protocol.getRobotRules(url(host));});
                assertTrue(secondStarted.await(1,TimeUnit.SECONDS));assertFalse(second.isDone());
                wire.release.countDown();
                assertSame(first.get(2,TimeUnit.SECONDS),second.get(2,TimeUnit.SECONDS));
                assertEquals(1,wire.requests.get());
            }finally{wire.release.countDown();protocol.cleanup();}
        }finally{callers.shutdownNow();assertTrue(callers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void cacheExpiresAtTenMinutesUsingMonotonicTicker() throws Exception {
        String host=TorPolicyTest.onion(1);AtomicLong now=new AtomicLong();
        try(Wire wire=new Wire(null)) {
            wire.bodies.add("User-agent: *\nDisallow: /allowed\n");
            wire.bodies.add("User-agent: *\nDisallow: /other\n");
            TorProtocol protocol=new TorProtocol(now::get);protocol.configure(config(wire.port(),host));
            try {
                BaseRobotRules original=protocol.getRobotRules(url(host));assertFalse(original.isAllowed(url(host)));
                now.set(TimeUnit.MINUTES.toNanos(10)-1);
                assertSame(original,protocol.getRobotRules(url(host)));assertEquals(1,wire.requests.get());
                now.incrementAndGet();BaseRobotRules refreshed=protocol.getRobotRules(url(host));
                assertNotSame(original,refreshed);assertTrue(refreshed.isAllowed(url(host)));assertEquals(2,wire.requests.get());
            }finally{protocol.cleanup();}
        }
    }
    @Test public void unsuccessfulResponseIsNotCachedAsRules() throws Exception {
        String host=TorPolicyTest.onion(1);
        try(Wire wire=new Wire(null)) {
            wire.statuses.add(503);wire.statuses.add(200);
            TorProtocol protocol=new TorProtocol();protocol.configure(config(wire.port(),host));
            try {
                IOException failure=assertThrows(IOException.class,()->protocol.getRobotRules(url(host)));
                assertEquals("ROBOTS_UNAVAILABLE",failure.getMessage());
                assertTrue(protocol.getRobotRules(url(host)).isAllowed(url(host)));assertEquals(2,wire.requests.get());
            }finally{protocol.cleanup();}
        }
    }
    @Test public void cleanupCancelsLoaderAndRejectsSameOriginWaiterWithoutPublishingCache() throws Exception {
        String host=TorPolicyTest.onion(1);ExecutorService callers=Executors.newFixedThreadPool(2);
        try(Wire wire=new Wire(host)) {
            wire.allowClientAbort=true;
            TorProtocol protocol=new TorProtocol();protocol.configure(config(wire.port(),host));
            try {
                Future<BaseRobotRules> first=callers.submit(()->protocol.getRobotRules(url(host)));
                assertTrue(wire.blocked.await(2,TimeUnit.SECONDS));
                CountDownLatch waiting=new CountDownLatch(1);
                Future<BaseRobotRules> second=callers.submit(()->{waiting.countDown();return protocol.getRobotRules(url(host));});
                assertTrue(waiting.await(1,TimeUnit.SECONDS));
                long start=System.nanoTime();protocol.cleanup();
                assertTrue("Cleanup blocked on network",System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));
                for(Future<BaseRobotRules> pending:List.of(first,second)) {
                    ExecutionException failure=assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof IOException);assertEquals("PROTOCOL_CLOSED",failure.getCause().getMessage());
                }
                assertEquals(1,wire.requests.get());
                assertEquals("PROTOCOL_CLOSED",assertThrows(IOException.class,()->protocol.getRobotRules(url(host))).getMessage());
            }finally{wire.release.countDown();protocol.cleanup();}
        }finally{callers.shutdownNow();assertTrue(callers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void cacheRetainsSixteenEntriesWithFifoEviction() throws Exception {
        String[] hosts=new String[16];for(int i=0;i<hosts.length;i++)hosts[i]=TorPolicyTest.onion(i+1);
        try(Wire wire=new Wire(null)) {
            TorProtocol protocol=new TorProtocol();protocol.configure(config(wire.port(),hosts));
            try {
                for(String host:hosts)protocol.getRobotRules(url(host));
                protocol.getRobotRules("http://"+hosts[0]+":80/allowed");
                assertEquals(17,wire.requests.get());
                protocol.getRobotRules(url(hosts[1]));assertEquals(17,wire.requests.get());
                protocol.getRobotRules(url(hosts[0]));assertEquals(18,wire.requests.get());
            }finally{protocol.cleanup();}
        }
    }

    private static final class Wire implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getLoopbackAddress());
        final ExecutorService workers=Executors.newCachedThreadPool();
        final CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        final BlockingQueue<Throwable> failures=new LinkedBlockingQueue<>();
        final AtomicInteger requests=new AtomicInteger();
        final Queue<String> bodies=new ConcurrentLinkedQueue<>();
        final Queue<Integer> statuses=new ConcurrentLinkedQueue<>();
        volatile boolean allowClientAbort;
        final String heldHost;
        volatile boolean closed;
        Wire(String heldHost) throws IOException {
            this.heldHost=heldHost;listener.setSoTimeout(100);
            workers.submit(()->{
                while(!closed)try {
                    Socket socket=listener.accept();workers.submit(()->serve(socket));
                }catch(SocketTimeoutException ignored){}catch(IOException e){if(!closed)failures.add(e);}
            });
        }
        int port(){return listener.getLocalPort();}
        void serve(Socket socket) {
            try(socket) {
                socket.setSoTimeout(5000);
                DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
                if(in.readUnsignedByte()!=5)throw new IOException("Expected SOCKS5");
                in.readNBytes(in.readUnsignedByte());out.write(new byte[]{5,0});out.flush();
                if(in.readUnsignedByte()!=5 || in.readUnsignedByte()!=1 || in.readUnsignedByte()!=0 || in.readUnsignedByte()!=3)
                    throw new IOException("Expected remote hostname SOCKS CONNECT");
                String host=new String(in.readNBytes(in.readUnsignedByte()),StandardCharsets.US_ASCII);
                if(in.readUnsignedShort()!=80)throw new IOException("Unexpected destination port");
                out.write(new byte[]{5,0,0,1,127,0,0,1,0,80});out.flush();
                ByteArrayOutputStream request=new ByteArrayOutputStream();
                while(request.size()<8192) {
                    int b=in.read();if(b<0)throw new EOFException();request.write(b);
                    if(request.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;
                }
                if(!request.toString(StandardCharsets.US_ASCII).startsWith("GET /robots.txt HTTP/1.1"))throw new IOException("Unexpected request");
                requests.incrementAndGet();
                if(host.equals(heldHost)){blocked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Fixture gate not released");}
                String body=bodies.poll();if(body==null)body="User-agent: *\nDisallow: /blocked\n";
                Integer status=statuses.poll();if(status==null)status=200;
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: text/plain\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);out.flush();
            }catch(Throwable e){if(!closed && !(allowClientAbort && e instanceof IOException))failures.add(e);}
        }
        public void close() throws Exception {
            closed=true;release.countDown();listener.close();workers.shutdownNow();
            assertTrue("Fixture workers leaked",workers.awaitTermination(2,TimeUnit.SECONDS));
            assertTrue("SOCKS fixture failed: "+failures,failures.isEmpty());
        }
    }
}
