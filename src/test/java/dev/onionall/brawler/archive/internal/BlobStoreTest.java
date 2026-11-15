package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверки integrity и no-replace публикации локальных HTML-объектов.</b>
 *
 * <p>Disposable filesystem tests не доказывают power-loss durability. Barriers
 * запускают конкурирующие publishers без временных предположений о scheduler.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class BlobStoreTest {
    private static final byte[] HTML = "<html>immutable</html>".getBytes(StandardCharsets.UTF_8);

    @Test public void verifiesContentAndRejectsMissingCorruptAndUnsafeDigests() throws Exception {
        Path objects = Files.createTempDirectory("blob-integrity");
        var store = new BlobStore(objects);
        String digest = BlobStore.hash(HTML);
        assertThrows(IOException.class, () -> store.verify(digest, HTML.length));
        assertThrows(IOException.class, () -> store.publish("../outside", HTML));
        assertThrows(IOException.class, () -> store.publish("0".repeat(64), HTML));
        store.publish(digest, HTML);
        store.verify(digest, HTML.length);
        store.publish(digest, HTML);
        Path destination = objects.resolve(digest + ".html");
        byte[] corruption = HTML.clone();
        corruption[1] ^= 1;
        Files.write(destination, corruption);
        assertThrows(IOException.class, () -> store.verify(digest, HTML.length));
        assertThrows(IOException.class, () -> store.publish(digest, HTML));
        assertArrayEquals(corruption, Files.readAllBytes(destination));
        try (var paths = Files.list(objects)) {
            assertEquals(1L, paths.count());
        }
    }

    @Test public void concurrentPublishersReuseCompleteImmutableWinner() throws Exception {
        Path objects = Files.createTempDirectory("blob-race");
        String digest = BlobStore.hash(HTML);
        int writers = 8;
        var start = new CyclicBarrier(writers);
        try (var executor = Executors.newFixedThreadPool(writers)) {
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < writers; i++) {
                results.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    new BlobStore(objects).publish(digest, HTML);
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get(15, TimeUnit.SECONDS);
            }
        }
        new BlobStore(objects).verify(digest, HTML.length);
        assertArrayEquals(HTML, Files.readAllBytes(objects.resolve(digest + ".html")));
        try (var paths = Files.list(objects)) {
            assertEquals(1L, paths.count());
        }
    }

    @Test public void competingConflictingCreationIsNeverReplaced() throws Exception {
        Path parent = Files.createTempDirectory("blob-conflicting-race");
        byte[] conflict = HTML.clone();
        conflict[1] ^= 1;
        Path competing = Files.write(parent.resolve("competitor"), conflict);
        String digest = BlobStore.hash(HTML);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 20; i++) {
                Path objects = Files.createDirectory(parent.resolve("objects-" + i));
                Path destination = objects.resolve(digest + ".html");
                var start = new CyclicBarrier(2);
                Future<Boolean> publisher = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        new BlobStore(objects).publish(digest, HTML);
                        return true;
                    } catch (IOException expectedConflict) {
                        return false;
                    }
                });
                Future<Boolean> competitor = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        Files.createLink(destination, competing);
                        return true;
                    } catch (FileAlreadyExistsException winnerAlreadyPublished) {
                        return false;
                    }
                });
                boolean conflictWon = competitor.get(15, TimeUnit.SECONDS);
                assertEquals(!conflictWon, publisher.get(15, TimeUnit.SECONDS));
                assertArrayEquals(conflictWon ? conflict : HTML, Files.readAllBytes(destination));
                try (var paths = Files.list(objects)) {
                    assertEquals(1L, paths.count());
                }
            }
        }
    }

    @Test public void symlinkObjectsAndDestinationAreRejected() throws Exception {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path parent = Files.createTempDirectory("blob-symlink");
        Path outside = Files.createDirectory(parent.resolve("outside"));
        Path objects = Files.createDirectory(parent.resolve("objects"));
        Path existing = Files.write(outside.resolve("existing"), HTML);
        String digest = BlobStore.hash(HTML);
        Files.createSymbolicLink(objects.resolve(digest + ".html"), existing);
        assertThrows(IOException.class, () -> new BlobStore(objects).publish(digest, HTML));
        assertThrows(IOException.class, () -> new BlobStore(objects).verify(digest, HTML.length));
        Path linkedObjects = Files.createSymbolicLink(parent.resolve("linked"), outside);
        assertThrows(IOException.class, () -> new BlobStore(linkedObjects).publish(digest, HTML));
        assertArrayEquals(HTML, Files.readAllBytes(existing));
    }

    @Test public void cancellationPreservesInterruptAndLeavesNoTemporaryObject() throws Exception {
        Path objects = Files.createTempDirectory("blob-interrupt");
        String digest = BlobStore.hash(HTML);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> new BlobStore(objects).publish(digest, HTML));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        try (var paths = Files.list(objects)) {
            assertEquals(0L, paths.count());
        }
    }

    @Test public void capacityAndHtmlBoundsRejectWithoutDeletingExistingData() throws Exception {
        Path objects = Files.createTempDirectory("blob-capacity");
        var store = new BlobStore(objects);
        byte[] empty = new byte[0];
        byte[] oversized = new byte[ArchiveStore.MAX_HTML_BYTES + 1];
        assertThrows(IOException.class, () -> store.publish(BlobStore.hash(empty), empty));
        assertThrows(IOException.class, () -> store.publish(BlobStore.hash(oversized), oversized));
        Path retained = objects.resolve("retained-object.html");
        try (FileChannel file = FileChannel.open(retained, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
            file.position(ArchiveStore.MAX_BLOB_BYTES - 1);
            file.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(IOException.class, () -> store.publish(BlobStore.hash(HTML), HTML));
        assertEquals(ArchiveStore.MAX_BLOB_BYTES, Files.size(retained));
        try (var paths = Files.list(objects)) {
            assertEquals(1L, paths.count());
        }
    }
}
