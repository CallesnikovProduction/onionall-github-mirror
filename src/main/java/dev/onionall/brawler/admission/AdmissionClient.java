package dev.onionall.brawler.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.number;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.text;

/**
 * <b>Ограниченный аутентифицированный RPC-клиент Frontier.</b>
 *
 * <p>Использует версионированный Command и deadline; stock/анонимного fallback нет.
 */
public final class AdmissionClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND = MethodDescriptor.<BytesValue,
        BytesValue>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
        .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance())).build();

    private final ManagedChannel channel;

    private final Channel authenticated;

    /**
     * <b>Создаёт канал с обязательным admission capability.</b>
     *
     * <p>Проверяет endpoint и токен до создания канала. RPC использует существующий
     * plaintext-контракт; transport TLS здесь не добавляется.
     *
     * @param host адрес Frontier, не onion-сервис
     * @param port порт Frontier
     * @param token capability ограниченной длины и алфавита
     * @throws IOException если endpoint или токен некорректны
     */
    public AdmissionClient(String host, int port, String token) throws IOException {
        if (host == null || !host.matches("[a-zA-Z0-9.-]{1,253}") || host.replaceAll("\\.+$", "")
            .toLowerCase(java.util.Locale.ROOT).endsWith(".onion") || port < 1 || port > 65535) {
            throw new IOException("Invalid admission endpoint");
        }
        if (token == null || token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Require a bounded admission capability token");
        }
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().maxInboundMessageSize(65536).build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER), token);
        authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    /**
     * <b>Создание клиента для взаимодействия с Frontier.</b>
     *
     * <p>Извлекает адрес и порт Frontier из runtime-конфигурации
     * и создаёт {@link AdmissionClient} с переданным
     * admission capability-токеном.
     *
     * @param runtime карта параметров среды выполнения
     * @param token токен авторизации admission-операций
     * @return настроенный клиент Frontier
     * @throws IOException если параметры подключения некорректны
     */
    public static AdmissionClient fromRuntime(Map<String, Object> runtime, String token) throws IOException {
        return new AdmissionClient(text(runtime, "frontier_host"), number(runtime, "frontier_port", 7071, 65535),
            token);
    }

    /**
     * <b>Выполняет bounded JSON-команду Frontier.</b>
     *
     * <p>Добавляет capability и deadline; ошибки RPC возвращает как IOException без fallback.
     *
     * @param request команда или контекст RPC
     * @return ответ авторитетного сервера
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    public Map<String, Object> command(Map<String, Object> request) throws IOException {
        byte[] body = JSON.writeValueAsBytes(request);
        if (body.length > 32768) {
            throw new IOException("Admission request exceeds32KiB");
        }
        try {
            BytesValue response = ClientCalls.blockingUnaryCall(authenticated, COMMAND,
                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS), BytesValue.newBuilder()
                .setValue(ByteString.copyFrom(body)).build());
            if (response.getValue().size() > 65536) {
                throw new IOException("Admission response exceeds64KiB");
            }
            return JSON.readValue(response.getValue().toByteArray(), new TypeReference<Map<String, Object>>() {
            });
        } catch (StatusRuntimeException e) {
            throw new IOException("Admission RPC failed: " + e.getStatus().getCode(), e);
        }
    }

    /**
     * <b>Читает проверенную страницу состояния сессии.</b>
     *
     * <p>Использует тот же аутентифицированный Command; проверяет cursor и лимит.
     *
     * @param session идентификатор или наблюдение сессии Frontier
     * @param cursor курсор предыдущей страницы либо null
     * @param limit максимальный размер страницы
     * @return проверенная страница Frontier
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    public FrontierReadback.Page list(String session, String cursor, int limit) throws IOException {
        if (session == null || !session.matches("[A-Za-z0-9-]{1,64}") || limit < 1 || limit > 200 ||
            cursor != null && (cursor.isEmpty() || cursor.length() > 256)) {
            throw new IOException("Invalid Frontier list request");
        }
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("action", "list");
        request.put("session_id", session);
        request.put("limit", limit);
        if (cursor != null) {
            request.put("cursor", cursor);
        }
        return FrontierReadback.page(command(request), session, cursor, limit);
    }

    /**
     * <b>Читает проверенные счётчики сессии.</b>
     *
     * <p>Не подменяет недоступный ответ локальными счётчиками.
     *
     * @param session идентификатор или наблюдение сессии Frontier
     * @return счётчики с отметкой времени
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    public FrontierReadback.Counts counts(String session) throws IOException {
        if (session == null || !session.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IOException("Invalid Frontier counts request");
        }
        return FrontierReadback.counts(command(Map.of("action", "counts", "session_id", session)), session);
    }

    /**
     * <b>Завершает канал RPC.</b>
     *
     * <p>Ожидает не более пяти секунд; восстанавливает interrupt flag.
     */
    @Override
    public void close() {
        channel.shutdownNow();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Admission channel did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
