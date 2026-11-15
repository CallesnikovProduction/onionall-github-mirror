package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * <b>Хранилище неизменяемых content-addressed HTML-объектов.</b>
 *
 * <p>Публикация требует владения archive connection и SQL catalog_guard: это
 * сериализует проверку capacity между сотрудничающими writers. Полностью записанный
 * и проверенный файл публикуется hard link без замены существующего объекта.
 * </p>
 *
 * Объекты после SQL rollback сохраняются. Каталог принадлежит доверенному локальному
 * оператору; проверки symlink не защищают от всех внешних filesystem races.
 * Без directory fsync гарантия переживания потери питания не предоставляется.
 *
 * TODO(BLOB-002): Define recovery and space-reclamation policy for
 *  orphan blobs and stale .publish-*.tmp files after rollback or crash.
 *  Current capacity accounting includes unreferenced regular files.
 *  Cleanup must verify catalog references, active publishers and
 *  archive ownership; never delete blobs solely by file age.
 *  Add recovery and quota-exhaustion regression tests.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class BlobStore {

    /** Каталог content-addressed HTML-объектов; lifecycle каталога принадлежит ArchiveContext. */
    private final Path objects;

    private final String suffix;

    /**
     * <b>Привязывает хранилище к уже созданному каталогу объектов.</b>
     *
     * @param objects каталог, которым владеет archive context
     */
    BlobStore(Path objects) {
        this(objects, ".html");
    }

    /** Выбирает фиксированный storage namespace; URL не участвует в имени файла. */
    BlobStore(Path objects, String suffix) {
        if (!Set.of(".html", ".txt").contains(suffix)) {
            throw new IllegalArgumentException("Unsupported archive object namespace");
        }
        this.objects = objects;
        this.suffix = suffix;
    }

    /**
     * <b>Вычисляет lowercase SHA-256 для content identity.</b>
     *
     * @param content неизменяемые во время вызова bytes
     * @return 64 hexadecimal символа
     * @throws IOException если JVM не предоставляет SHA-256
     */
    static String hash(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException e) {
            throw ArchiveSupport.failure(e);
        }
    }

    /**
     * <b>Проверяет complete объект по имени, размеру и digest.</b>
     *
     * <p>Не исправляет и не удаляет повреждённые файлы. Read выполняется при
     * владении archive connection, без необходимости начинать SQL transaction.
     *
     * @param digest ожидаемый lowercase SHA-256
     * @param expectedSize размер исходного HTML
     * @throws IOException при отсутствующем, повреждённом или небезопасном объекте
     */
    void verify(
            String digest,
            int expectedSize
    ) throws IOException {
        verifyFile(destination(digest), digest, expectedSize);
    }

    /**
     * Читает только проверенные bounded bytes для derived research/export.
     * @param digest ожидаемый SHA-256
     * @param expectedSize ожидаемый размер 1..131072
     * @return новая byte array
     * @throws IOException при отсутствующем или повреждённом объекте
     */
    byte[] read(String digest, int expectedSize) throws IOException {
        Path file = destination(digest);
        verifyFile(file, digest, expectedSize);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != expectedSize || !hash(bytes).equals(digest)) {
            throw new IOException("Archive object changed during read");
        }
        return bytes;
    }

    /**
     * <b>Публикует проверенный HTML без замены существующего destination.</b>
     *
     * <p>Вызывается внутри catalog_guard transaction. Existing winner обязательно
     * проверяется; hard-link unsupported завершает запись ошибкой без move fallback.
     * Temp force предшествует публикации; SQL commit выполняет только coordinator.
     *
     * <p>Повторная публикация того же digest не создаёт новый объект:
     * существующий файл проверяется по размеру и SHA-256.
     * Повреждённый объект не заменяется автоматически.
     *
     * @param digest SHA-256 переданных bytes
     * @param html bounded HTML, неизменяемый на протяжении вызова
     * @throws IOException при cancellation, capacity, integrity или filesystem failure
     */
    void publish(
            String digest,
            byte[] html
    ) throws IOException {
        cancelled();
        Path destination = destination(digest);
        if (
                html == null ||
                html.length < 1 ||
                html.length > ArchiveStore.MAX_HTML_BYTES ||
                !hash(html).equals(digest)
        ) {
            throw new IOException("Invalid content-addressed HTML");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            verifyFile(destination, digest, html.length);
            return;
        }
        long used = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(objects)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    throw new IOException("Unexpected archive object");
                }
                long size;
                try {
                    size = Files.size(file);
                } catch (NoSuchFileException removedTemporary) {
                    // A concurrent publisher may have removed its private temp.
                    continue;
                }
                if (size > ArchiveStore.MAX_BLOB_BYTES - used) {
                    throw new IOException("Archive64MiB storage limit reached; no automatic deletion");
                }
                used += size;
            }
        }
        if (html.length > ArchiveStore.MAX_BLOB_BYTES - used) {
            throw new IOException("Archive64MiB storage limit reached; no automatic deletion");
        }
        Path temporary = Files.createTempFile(objects, ".publish-", ".tmp");
        Throwable primary = null;
        try {
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)
            ) {
                ByteBuffer data = ByteBuffer.wrap(html);
                while (data.hasRemaining()) {
                    output.write(data);
                }
                output.force(true);
            }
            verifyFile(temporary, digest, html.length);
            cancelled();
            try {
                // TODO(BLOB-001): Define crash-durability guarantees for published blobs.
                //  FileChannel.force(true) flushes file content but does not guarantee
                //  persistence of the destination directory entry created by hard link.
                //  Evaluate supported directory fsync or an explicit recovery protocol
                //  before claiming power-loss-safe SQL-to-blob consistency.
                //  Add fault-injection tests where practical.
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException e) {
                verifyFile(destination, digest, html.length);
            } catch (UnsupportedOperationException e) {
                throw new IOException("Archive filesystem requires atomic hard-link publication", e);
            }
        } catch (IOException | RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanup) {
                if (primary != null) {
                    primary.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    /** Проверяет digest до построения пути и исключает известные symlink. */
    private Path destination(String digest) throws IOException {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid archive content hash");
        }
        Path destination = objects.resolve(digest + suffix);
        ArchiveSupport.noSymlinks(destination);
        return destination;
    }

    /** Читает bytes только после bounded size check; не заменяет повреждённый объект. */
    private static void verifyFile(
            Path file,
            String digest,
            int expectedSize
    ) throws IOException {
        ArchiveSupport.noSymlinks(file);
        if (expectedSize < 1 || expectedSize > ArchiveStore.MAX_HTML_BYTES
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || Files.size(file) != expectedSize || !hash(Files.readAllBytes(file)).equals(digest)) {
            throw new IOException("Content-addressed snapshot object missing/inconsistent");
        }
    }

    /** Сохраняет interrupt flag, прекращая работу до публикации нового объекта. */
    private static void cancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Archive cancelled");
        }
    }
}
