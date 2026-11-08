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
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.number;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.text;

/**
 * <b>Ограниченный аутентифицированный RPC-клиент Frontier.</b>
 *
 * <p>Использует версионированный Command и deadline; stock/анонимного fallback нет.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class AdmissionClient implements AutoCloseable {

    private static final ObjectMapper ADMISSION_JSON_MAPPER = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int REQUEST_LIMIT = 32768;
    private static final int RESPONSE_LIMIT = 65536;
    private static final Set<String> READ_ACTIONS = Set.of("session", "reservation", "list", "counts");

    private static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
            MethodDescriptor
                    .<BytesValue,
        BytesValue>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("onionall.frontier.admission.v1.Admission/Command")
                    .setRequestMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(BytesValue.getDefaultInstance()))
                    .build();

    private final ManagedChannel channel;

    private final Channel authenticated;

    private final CallCredentials credentials;

    private final long deadlineMillis;

    public enum FailureKind { TRANSPORT, PROTOCOL }

    /**
     * <b>Ошибка RPC, не являющаяся авторитетным отказом admission.</b>
     *
     * <p>Для мутации исход остаётся UNKNOWN. Обычная цепочка причин содержит
     * только безопасный gRPC code; исходная ошибка доступна явно для диагностики,
     * но может содержать недоверенные server descriptions/trailers и не должна логироваться.
     */
    public static final class RpcFailure extends IOException {
        private final FailureKind kind;
        private final boolean mutationOutcomeUnknown;
        private final StatusRuntimeException transportFailure;

        private RpcFailure(FailureKind kind, boolean mutation, StatusRuntimeException failure) {
            super("Admission RPC " + (mutation ? "outcome UNKNOWN" : "readback unavailable") + ": " +
                (failure == null ? "PROTOCOL_VIOLATION" : failure.getStatus().getCode()),
                failure == null ? null : Status.fromCode(failure.getStatus().getCode()).asRuntimeException());
            this.kind = kind;
            this.mutationOutcomeUnknown = mutation;
            this.transportFailure = failure;
        }

        public FailureKind kind() { return kind; }
        public boolean mutationOutcomeUnknown() { return mutationOutcomeUnknown; }
        public Status.Code statusCode() {
            return transportFailure == null ? null : transportFailure.getStatus().getCode();
        }
        public StatusRuntimeException transportFailure() { return transportFailure; }
    }

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
    public AdmissionClient(
            String host,
            int port,
            String token
    ) throws IOException {
        this(host, port, token, null, 10000);
    }

    AdmissionClient(String host, int port, String token, long deadlineMillis) throws IOException {
        this(host, port, token, null, deadlineMillis);
    }

    private AdmissionClient(String host, int port, String token, CallCredentials credentials,
                            long deadlineMillis) throws IOException {
        if (host == null || !host.matches("[a-zA-Z0-9.-]{1,253}") || host.replaceAll("\\.+$", "")
            .toLowerCase(java.util.Locale.ROOT).endsWith(".onion") || port < 1 || port > 65535) {
            throw new IOException("Invalid admission endpoint");
        }
        if (credentials == null && (token == null || token.length() < 32 || token.length() > 256 ||
            !token.matches("[A-Za-z0-9_-]+"))) {
            throw new IOException("Require a bounded admission capability token");
        }
        if (deadlineMillis < 1 || deadlineMillis > 10000) {
            throw new IOException("Invalid admission deadline");
        }
        this.credentials = credentials;
        this.deadlineMillis = deadlineMillis;

        // TODO(RPC-001): Произвольный runtime endpoint не доказывает изоляцию plaintext.
        // Поддерживается только loopback/изолированная сеть без опубликованного Frontier-порта.
        // Для недоверенных связей нужен согласованный TLS transport клиента, legacy RPC и сервера.
        channel = ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .disableRetry()
                    .maxInboundMessageSize(RESPONSE_LIMIT + 16) // BytesValue framing; JSON bounded below.
                    .build();
        if (credentials == null) {
            // Explicit string API is a snapshot; production file-backed clients reload per RPC.
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-onionall-admission-token", Metadata.ASCII_STRING_MARSHALLER), token);
            authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
        } else {
            authenticated = channel;
        }
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
        return new AdmissionClient(
                text(runtime, "frontier_host"),
                number(runtime, "frontier_port", 7071, 65535),
                token
        );
    }

    /**
     * <b>Создаёт клиент с повторным чтением capability-файла перед каждым RPC.</b>
     * @param host адрес Frontier в доверенной транспортной границе
     * @param port порт Frontier
     * @param file operator-owned capability-файл
     * @return аутентифицированный клиент
     * @throws IOException при некорректном endpoint или недоступной capability
     */
    public static AdmissionClient fromCapabilityFile(String host, int port, Path file) throws IOException {
        return new AdmissionClient(host, port, null, FrontierCapability.fromFile(file), 10000);
    }

    /**
     * <b>Создаёт production-клиент из runtime и пути к capability-файлу.</b>
     * @param runtime проверенная конфигурация endpoint
     * @param file operator-owned capability-файл
     * @return клиент с перечитываемой capability
     * @throws IOException при некорректной конфигурации или capability
     */
    public static AdmissionClient fromRuntimeWithCapabilityFile(Map<String, Object> runtime, Path file)
        throws IOException {
        return fromCapabilityFile(text(runtime, "frontier_host"),
            number(runtime, "frontier_port", 7071, 65535), file);
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
        return command(request, deadlineMillis);
    }

    /** A caller's remaining operation budget may shorten, never extend, the RPC deadline. */
    Map<String, Object> command(Map<String, Object> request, long remainingMillis) throws IOException {
        if (remainingMillis < 1) {
            throw new IOException("Admission operation budget exhausted before dispatch");
        }
        if (request == null || !(request.get("action") instanceof String action) || action.isBlank()) {
            throw new IOException("Invalid admission command");
        }
        boolean mutation = !READ_ACTIONS.contains(action);
        byte[] body = encode(request);
        try {
            CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(
                Math.min(deadlineMillis, remainingMillis), TimeUnit.MILLISECONDS);
            if (credentials != null) options = options.withCallCredentials(credentials);
            BytesValue response = ClientCalls.blockingUnaryCall(authenticated, COMMAND,
                options, BytesValue.newBuilder()
                .setValue(ByteString.copyFrom(body)).build());
            if (response == null || response.getValue().size() > RESPONSE_LIMIT || !response.getValue().isValidUtf8()) {
                throw new RpcFailure(FailureKind.PROTOCOL, mutation, null);
            }
            Map<String, Object> reply;
            try {
                reply = ADMISSION_JSON_MAPPER.readValue(response.getValue().toStringUtf8(),
                    new TypeReference<Map<String, Object>>() { });
            } catch (IOException | RuntimeException invalid) {
                // Parser messages can contain the server payload, including echoed credentials.
                throw new RpcFailure(FailureKind.PROTOCOL, mutation, null);
            }
            if (reply == null || !(reply.get("outcome") instanceof String outcome) ||
                !outcome.matches("[A-Z_]{1,64}")) {
                throw new RpcFailure(FailureKind.PROTOCOL, mutation, null);
            }
            return reply;
        } catch (StatusRuntimeException e) {
            throw new RpcFailure(FailureKind.TRANSPORT, mutation, e);
        }
    }

    private static byte[] encode(Map<String, Object> request) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var bounded = new OutputStream() {
            public void write(int value) throws IOException {
                if (bytes.size() == REQUEST_LIMIT) throw new IOException("Admission request exceeds32KiB");
                bytes.write(value);
            }
            public void write(byte[] value, int offset, int length) throws IOException {
                if (length > REQUEST_LIMIT - bytes.size()) throw new IOException("Admission request exceeds32KiB");
                bytes.write(value, offset, length);
            }
        }) {
            ADMISSION_JSON_MAPPER.writeValue(bounded, request);
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("Invalid or oversized admission request (limit32KiB)");
        }
        return bytes.toByteArray();
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
    public FrontierReadback.Page list(
            String session,
            String cursor,
            int limit
    ) throws IOException {

        if (
                session == null ||
                !session.matches("[A-Za-z0-9-]{1,64}") ||
                limit < 1 ||
                limit > FrontierReadback.MAX_PAGE_SIZE ||
                cursor != null && (cursor.isEmpty() || cursor.length() > 256)
        ) {
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
                // In try-with-resources this is suppressed under the original RPC failure.
                throw new IllegalStateException("Admission channel did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
