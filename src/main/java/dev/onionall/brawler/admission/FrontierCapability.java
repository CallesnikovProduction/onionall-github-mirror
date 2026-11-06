package dev.onionall.brawler.admission;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * <b>Capability для каждого legacy RPC Frontier.</b>
 *
 * <p>Ошибка загрузки секрета запрещает запрос; анонимного повтора нет.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class FrontierCapability extends CallCredentials {

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
        Object configured = conf.get("admission.token.file");
        if (configured == null || configured.toString().isBlank()) {
            throw new IllegalArgumentException("Require admission.token.file for Frontier RPC");
        }
        FrontierCapability capability = new FrontierCapability(Path.of(configured.toString()));
        try {
            capability.read();
        } catch (IOException e) {
            throw new IllegalArgumentException("Frontier capability unavailable");
        }
        return capability;
    }

    /**
     * <b>Читает capability с проверкой алфавита.</b>
     *
     * <p>Не сохраняет секрет в журнале.
     *
     * @return проверенный токен
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    private String read() throws IOException {
        String token = token(file);
        if (!token.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid capability encoding");
        return token;
    }

    /**
     * <b>Добавляет capability к конкретному RPC.</b>
     *
     * <p>Читает секрет через executor; ошибки приводят к PERMISSION_DENIED без раскрытия файла.
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
        try {
            executor.execute(() -> {
                try {
                    Metadata headers = new Metadata();
                    headers.put(HEADER, read());
                    applier.apply(headers);
                } catch (IOException | RuntimeException e) {
                    // Never propagate file contents, credentials or filesystem exceptions to logs.
                    applier.fail(Status.PERMISSION_DENIED.withDescription("Frontier capability unavailable"));
                }
            });
        } catch (RuntimeException e) {
            applier.fail(Status.PERMISSION_DENIED.withDescription("Frontier capability unavailable"));
        }
    }

    /**
     * <b>Загрузка и проверка admission capability-токена.</b>
     *
     * <p>Считывает секрет из обычного файла размером не более 257 байт,
     * удаляет крайние пробельные символы и проверяет длину
     * от 32 до 256 символов и допустимый алфавит.
     *
     * @param file путь к файлу с admission capability
     * @return проверенный токен
     * @throws IOException если файл недоступен или токен некорректен
     */
    public static String token(Path file) throws IOException {
        if (
                file == null ||        // Путь не передали в целом
                !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || // НЕ обычный файл
                Files.size(file) > 257 // Размерность больше 257 байт (256 ASCII + 1 байт на \n)
        ) {
            throw new IOException("Require regular admission capability file <= 257 bytes");
        }
        // Чтение и очистка токена от пробельных символов в начале и в конце строки
        String token = Files.readString(file).strip();
        if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Invalid admission capability");
        }
        return token;
    }

    @Override
    public void thisUsesUnstableApi() {
    }
}
