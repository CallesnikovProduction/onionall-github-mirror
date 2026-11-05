package org.apache.stormcrawler.urlfrontier;

import static org.junit.Assert.*;
import static org.apache.stormcrawler.urlfrontier.CapabilityClientsTest.*;
import crawlercommons.urlfrontier.Urlfrontier.AckMessage;
import crawlercommons.urlfrontier.Urlfrontier.URLItem;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class CapabilityReliabilityTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final String URL = "http://fixture.invalid/seed";

    private void send(CapabilityStatusUpdaterBolt bolt) {
        bolt.store(URL, Status.FETCHED, new Metadata(), Optional.empty(), tuple());
    }

    @Test
    public void partialAndRepeatedCleanupAreSafe() {
        var bolt = new CapabilityStatusUpdaterBolt();
        bolt.cleanup();
        bolt.cleanup();
        var spout = new CapabilitySpout();
        spout.close();
        spout.close();
    }

    @Test
    public void lateAckCannotAcknowledgeReplacement() throws Exception {
        try (var f = new Fixture()) {
            f.hold = true;
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            try {
                send(bolt);
                await("first write", () -> f.writes.size() == 1);
                var old = f.writes.get(0);
                var cache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) field(bolt, "waitAck");
                cache.policy().expireAfterWrite().orElseThrow().setExpiresAfter(java.time.Duration.ZERO);
                cache.cleanUp();
                await("expired tuple failed", () -> fail.get() == 1);
                cache.policy().expireAfterWrite().orElseThrow().setExpiresAfter(java.time.Duration.ofSeconds(60));
                send(bolt);
                await("second write", () -> f.writes.size() == 2);
                assertNotEquals(old.getID(), f.writes.get(1).getID());
                f.responses.get(0).onNext(AckMessage.newBuilder().setID(old.getID()).build());
                Thread.sleep(100);
                assertEquals(0, ack.get());
                f.responses.get(0).onNext(AckMessage.newBuilder().setID(f.writes.get(1).getID()).build());
                await("replacement ack", () -> ack.get() == 1);
            } finally {
                bolt.cleanup();
            }
        }
    }

    @Test
    public void rpcFailureRecoversOnNextSubmissionAndFailsPending() throws Exception {
        try (var f = new Fixture()) {
            f.hold = true;
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            try {
                send(bolt);
                await("write", () -> f.writes.size() == 1);
                f.responses.get(0).onError(io.grpc.Status.UNAVAILABLE.asRuntimeException());
                await("pending failed", () -> fail.get() == 1);
                assertEquals(io.grpc.ConnectivityState.READY, ((io.grpc.ManagedChannel) field(bolt, "channel")).getState(false));
                String rotated = "synthetic-capability-rotated-cccccccccccccccc";
                f.expected.set(rotated);
                Files.writeString(token, rotated);
                f.hold = false;
                send(bolt);
                await("recovered authenticated stream", () -> f.streams.get() == 2 && ack.get() == 1);
            } finally {
                bolt.cleanup();
            }
        }
    }

    private Object field(Object value, String name) throws Exception {
        var field = CapabilityStatusUpdaterBolt.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    @Test
    public void interruptedPermitWaitFailsOnceAndPreservesInterrupt() throws Exception {
        try (var f = new Fixture()) {
            f.hold = true;
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            try {
                for (int i = 0; i < 10; i++) {
                    send(bolt);
                }
                var interrupted = new AtomicBoolean();
                Thread thread = new Thread(() -> {
                    send(bolt);
                    interrupted.set(Thread.currentThread().isInterrupted());
                });
                thread.setDaemon(true);
                thread.start();
                Semaphore permits = (Semaphore) field(bolt, "inFlightSemaphore");
                await("waiter blocked on permit", () -> permits.hasQueuedThreads());
                thread.interrupt();
                thread.join(1000);
                assertFalse(thread.isAlive());
                assertTrue(interrupted.get());
                assertEquals(1, fail.get());
                assertEquals(0, ((Semaphore) field(bolt, "inFlightSemaphore")).availablePermits());
            } finally {
                bolt.cleanup();
            }
            assertEquals(11, fail.get());
            assertEquals(10, ((Semaphore) field(bolt, "inFlightSemaphore")).availablePermits());
        }
    }

    @Test
    public void completionRecoveryAndPermanentDenialDoNotLoop() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            try {
                send(bolt);
                await("ack", () -> ack.get() == 1);
                f.responses.get(0).onCompleted();
                await("stream ended", () -> {
                    synchronized (fieldLock(bolt)) {
                        try {
                            return field(bolt, "requestObserver") == null;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                send(bolt);
                await("second stream", () -> ack.get() == 2 && f.streams.get() == 2);
                f.responses.get(1).onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                await("denied", () -> {
                    synchronized (fieldLock(bolt)) {
                        try {
                            return Boolean.TRUE.equals(field(bolt, "authorizationFailed"));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                for (int i = 0; i < 3; i++) {
                    send(bolt);
                }
                assertEquals(3, fail.get());
                assertEquals(2, f.streams.get());
            } finally {
                bolt.cleanup();
                bolt.cleanup();
            }
        }
    }

    private Object fieldLock(Object bolt) {
        try {
            return field(bolt, "streamLock");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void concurrentSubmissionsAndTerminationConservePermits() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            var pool = Executors.newFixedThreadPool(4);
            try {
                send(bolt);
                await("initial", () -> ack.get() == 1);
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 40; i++) {
                    futures.add(pool.submit(() -> send(bolt)));
                }
                f.responses.get(0).onError(io.grpc.Status.UNAVAILABLE.asRuntimeException());
                for (var future : futures) {
                    future.get(8, TimeUnit.SECONDS);
                }
                await("all resolved", () -> ack.get() + fail.get() == 41);
                assertEquals(10, ((Semaphore) field(bolt, "inFlightSemaphore")).availablePermits());
                bolt.cleanup();
                int streams = f.streams.get();
                send(bolt);
                assertEquals(streams, f.streams.get());
                assertEquals(42, ack.get() + fail.get());
            } finally {
                pool.shutdownNow();
                bolt.cleanup();
            }
        }
    }

    @Test
    public void duplicateDiscoveryStillAcknowledgesWithoutSecondWrite() throws Exception {
        try (var f = new Fixture()) {
            f.hold = true;
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var ack = new AtomicInteger();
            var fail = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(ack, fail));
            try {
                send(bolt);
                await("write", () -> f.writes.size() == 1);
                bolt.store(URL, Status.DISCOVERED, new Metadata(), Optional.empty(), tuple());
                assertEquals(1, ack.get());
                assertEquals(1, f.writes.size());
                bolt.cleanup();
                assertEquals(1, fail.get());
            } finally {
                bolt.cleanup();
            }
        }
    }

    @Test
    public void shutdownExceptionStillClosesChannelAndDrainsPending() throws Exception {
        try (var f = new Fixture()) {
            f.hold = true;
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            var failed = new AtomicInteger();
            var bolt = new CapabilityStatusUpdaterBolt();
            bolt.prepare(conf, new MetricsContext(conf), collector(new AtomicInteger(), failed));
            send(bolt);
            await("pending write", () -> f.writes.size() == 1);
            var field = CapabilityStatusUpdaterBolt.class.getDeclaredField("requestObserver");
            field.setAccessible(true);
            field.set(bolt, new io.grpc.stub.StreamObserver<URLItem>() {

                public void onNext(URLItem value) {
                }

                public void onError(Throwable error) {
                }

                public void onCompleted() {
                    throw new IllegalStateException("shutdown fixture");
                }
            });
            assertThrows(IllegalStateException.class, bolt::cleanup);
            assertTrue(((io.grpc.ManagedChannel) field(bolt, "channel")).isShutdown());
            assertEquals(1, failed.get());
            bolt.cleanup();
            assertEquals(1, failed.get());
        }
    }

    @Test
    public void failedPrepareCanBeCleanedWithoutMaskingFailure() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            Path token = temporary.newFile().toPath();
            Files.writeString(token, f.expected.get());
            var conf = conf(f, token);
            conf.put("urlfrontier.port", -1);
            var bolt = new CapabilityStatusUpdaterBolt();
            assertThrows(IllegalArgumentException.class, () -> bolt.prepare(conf, new MetricsContext(conf), collector(new AtomicInteger(), new AtomicInteger())));
            bolt.cleanup();
            bolt.cleanup();
            var spout = new CapabilitySpout();
            assertThrows(IllegalArgumentException.class, () -> spout.open(conf, new MetricsContext(conf), new org.apache.storm.spout.SpoutOutputCollector(null)));
            spout.close();
            spout.close();
        }
    }
}
