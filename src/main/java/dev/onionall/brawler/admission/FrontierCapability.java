package dev.onionall.brawler.admission;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>Capability для аутентифицированных RPC Frontier.</b>
 *
 * <p>Ошибка загрузки секрета запрещает запрос; анонимного повтора нет.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class FrontierCapability extends CallCredentials {

    /*
     * AdmissionRpc.guard owns authorization: this shared token permits Command,
     * PutURLs and GetURLs across sessions; administrative mutations stay denied.
     * It does not distinguish operator/worker authority or revoke active streams.
     * Plaintext deployment isolation is a separate transport precondition (RPC-001).
     */
    private static final Metadata.Key<String> HEADER = Metadata.Key.of(
            "x-onionall-admission-token",
            Metadata.ASCII_STRING_MARSHALLER
    );

    private final Path file;

    private FrontierCapability(Path file) {
        this.file = file;
    }

    /**
     * <b>Создаёт credentials из файла capability.</b>
     *
     * <p>Проверяет доступность секрета до создания клиента; не включает содержимое файла в сообщение ошибки.
     *
     * @param conf конфигурация компонента
     * @return credentials для Frontier RPC
     */
    public static FrontierCapability from(Map<String, Object> conf) {
        try {
            Object configured = conf.get("admission.token.file");
            if (configured == null || configured.toString().isBlank()) {
                throw new IllegalArgumentException("Require admission.token.file for Frontier RPC");
            }
            return fromFile(Path.of(configured.toString()));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Frontier capability unavailable");
        }
    }

    /**
     * <b>Создаёт credentials с проверкой файла до создания клиента.</b>
     *
     * <p>Перечитывает файл при каждом новом RPC; открытый stream не переавторизуется.
     *
     * @param file путь к capability-файлу
     * @return credentials для последующих RPC
     * @throws IOException если capability недоступна или некорректна
     */
    static FrontierCapability fromFile(Path file) throws IOException {
        token(file);
        return new FrontierCapability(file);
    }

    /**
     * <b>Добавляет capability к конкретному RPC.</b>
     *
     * <p>Читает секрет через executor; ошибки приводят к PERMISSION_DENIED без раскрытия файла.
     * Единственный terminal callback выбирается до его вызова, включая ошибку самого callback.
     *
     * @param request команда или контекст RPC
     * @param executor executor обработки credentials
     * @param applier получатель metadata либо отказа
     */
    @Override
    public void applyRequestMetadata(
            RequestInfo request,
            Executor executor,
            MetadataApplier applier
    ) {
        AtomicBoolean completed = new AtomicBoolean();
        try {
            executor.execute(() -> {
                Metadata headers;
                try {
                    headers = new Metadata();
                    headers.put(HEADER, token(file));
                } catch (IOException | RuntimeException e) {
                    fail(applier, completed);
                    return;
                }
                if (completed.compareAndSet(false, true)) {
                    applier.apply(headers);
                }
            });
        } catch (RuntimeException e) {
            // Includes rejection and executors that throw after starting the task.
            fail(applier, completed);
        }
    }

    private static void fail(MetadataApplier applier, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            applier.fail(Status.PERMISSION_DENIED.withDescription("Frontier capability unavailable"));
        }
    }

    /**
     * <b>Загрузка и проверка admission capability-токена.</b>
     *
     * <p>Проверяет обычный файл, открывает без перехода по конечной symlink и
     * считывает не более 258 байт для проверки лимита 257 байт,
     * удаляет крайние пробельные символы и проверяет длину
     * от 32 до 256 символов и допустимый алфавит.
     * Родительский каталог и его ACL должны контролироваться оператором;
     * path-based проверка типа не защищает от hostile replacement специальным файлом.
     * POSIX-only ограничения доступа не вводятся: доступны также Windows ACL и
     * существующие operator-owned файлы с правами чтения для worker.
     *
     * @param file путь к файлу с admission capability
     * @return проверенный токен
     * @throws IOException если файл недоступен или токен некорректен
     */
    public static String token(Path file) throws IOException {
        try {
            if (file == null) {
                throw new IOException("Frontier capability unavailable");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > 257) {
                throw new IOException("Frontier capability unavailable");
            }
            /*
             * TODO(CAP-002): the type check above is path-based, not fstat on the
             * opened handle. NOFOLLOW prevents final-component symlink following,
             * and readToken enforces the actual byte bound. A hostile directory
             * owner can still substitute a special file (including a blocking FIFO)
             * between check and open. A portable handle-bound type/nonblocking-open
             * mechanism is required before claiming protection against that actor.
             */
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return readToken(channel);
            }
        } catch (IOException | RuntimeException e) {
            // No filesystem exception, path or file content survives this boundary.
            throw new IOException("Frontier capability unavailable");
        }
    }

    /**
     * <b>Проверяет bounded содержимое уже открытого файла.</b>
     *
     * <p>Замена path не меняет handle или лимит; владение каналом остаётся у caller.
     *
     * @param channel открытый канал capability-файла
     * @return проверенный токен
     * @throws IOException если чтение или формат токена нарушают контракт
     */
    static String readToken(SeekableByteChannel channel) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(258);
        while (channel.read(bytes) != -1) {
            if (!bytes.hasRemaining()) {
                throw new IOException("Frontier capability unavailable");
            }
        }
        bytes.flip();
        String token = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString().strip();
        if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Frontier capability unavailable");
        }
        return token;
    }

    @Override
    public void thisUsesUnstableApi() {
    }
}
