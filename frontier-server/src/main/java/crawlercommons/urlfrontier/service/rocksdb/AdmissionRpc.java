// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import com.google.gson.*;
import com.google.protobuf.*;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * <b>Предоставляет защищённый RPC-доступ к операциям допуска URLFrontier.</b>
 *
 * <p>Объявляет командный gRPC-метод Admission/Command,
 * преобразует запросы между protobuf и JSON и передаёт
 * их на обработку службе RocksDBService.</p>
 *
 * <p>Создаёт перехватчик, который проверяет capability-токен
 * для операций изменения состояния и выдачи резервирований,
 * разрешает установленный набор методов чтения
 * и запрещает административные и неизвестные операции.</p>
 *
 * <p>Не выполняет планирование URL, не управляет очередями
 * и не изменяет постоянное состояние самостоятельно.
 * Проверки предметных ограничений и выполнение команд
 * остаются ответственностью службы Frontier.</p>
 *
 * @author GPT-6.1 Sol
 * @Reviewer: Stephan Kolesnikov
 */
public final class AdmissionRpc {

    /**
     * Полное имя защищённого RPC-метода выполнения
     * команд Admission.
     */
    public static final String METHOD = "onionall.frontier.admission.v1.Admission/Command";

    // TODO(rpc-policy): Сверить список разрешённых методов
    //  с актуальным protobuf-контрактом URLFrontier.
    //  Добавить тест, перебирающий все зарегистрированные RPC:
    //  защищённые операции требуют токен, разрешённые чтения
    //  не изменяют состояние, остальные операции запрещены.

    /**
     * Набор RPC-методов, для которых обязательно
     * предъявление действительного capability-токена.
     *
     * <p>Включает командный метод Admission,
     * добавление URL и получение URL с резервированием.</p>
     */
    private static final Set<String> CAPABILITY_METHODS = Set.of(
            METHOD,
            URLFrontierGrpc.getPutURLsMethod().getFullMethodName(),
            URLFrontierGrpc.getGetURLsMethod().getFullMethodName()
    );

    /**
     * Набор методов основной службы URLFrontier,
     * доступных без capability-токена.
     *
     * <p>Содержит только явно разрешённые операции чтения.
     * Не предоставляет доступ к административным методам.</p>
     */
    private static final Set<String> READ_METHODS = Set.of(
            "GetURLStatus", "ListURLs", "CountURLs", "ListCrawls",
            "ListQueues", "GetStats", "GetActive", "ListNodes"
    );

    /**
     * Описание одиночного RPC-метода Admission/Command.
     *
     * <p>Определяет имя метода, тип вызова и преобразование
     * protobuf-сообщений BytesValue для запроса и ответа.
     * Содержимое сообщений представляет собой JSON в UTF-8.</p>
     */
    public static final MethodDescriptor<BytesValue, BytesValue> COMMAND =
            MethodDescriptor
                    .<BytesValue, BytesValue> newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(METHOD)
                    .setRequestMarshaller(ProtoUtils.marshaller(
                            BytesValue.getDefaultInstance()
                    ))
                    .setResponseMarshaller(ProtoUtils.marshaller(
                            BytesValue.getDefaultInstance()
                    ))
                    .build();

    /**
     * <b>Создаёт gRPC-службу для выполнения команд Admission.</b>
     *
     * <p>Регистрирует одиночный метод Admission/Command,
     * который принимает JSON в protobuf-обёртке BytesValue.
     * Проверяет кодировку и размер запроса, преобразует
     * содержимое в JsonObject и передаёт команду
     * на выполнение службе RocksDBService.</p>
     *
     * <p>Полученный результат сериализуется в JSON
     * и возвращается клиенту. Ошибки запроса и внутренние
     * ошибки преобразуются в статусы gRPC.</p>
     *
     * <p>Метод не устанавливает проверку полномочий
     * самостоятельно. При регистрации полученной службы
     * необходимо применять перехватчик {@link #guard(String)}.</p>
     *
     * @param service служба RocksDB, выполняющая команды Admission
     * @return описание gRPC-службы с обработчиком команд
     */
    public static ServerServiceDefinition bind(RocksDBService service) {
        return ServerServiceDefinition
                .builder("onionall.frontier.admission.v1.Admission")
                .addMethod(
                        COMMAND,
                        ServerCalls.asyncUnaryCall((request,observer) -> {
                            // TODO(rpc-errors): Проверить поведение RPC при неожиданных
                            //  исключениях admissionCommand() и сериализации ответа.
                            //  Обеспечить предсказуемый статус INTERNAL, запись причины
                            //  в серверный журнал и отсутствие раскрытия внутренних данных.
                            //  Не маскировать ошибки программирования под недоступность RocksDB.
                            try {
                                if (!request.getValue().isValidUtf8())
                                    throw new IllegalArgumentException("Require UTF8");

                                // TODO(rpc-limits): Проверить верхнюю границу размера
                                //  ответа admissionCommand() и ограничения числа записей.
                                //  При необходимости ввести ограничение результата
                                //  или постраничную выдачу. Добавить тест большого ответа.
                                if (request.getValue().size() > 32768)
                                    throw new IllegalArgumentException("Admission request limit32KiB");

                                String text = request.getValue().toStringUtf8();

                                // TODO(rpc-validation): Проверять, что корневой элемент
                                //  SON является объектом, до вызова getAsJsonObject().
                                //  Для массивов, примитивов и null возвращать INVALID_ARGUMENT,
                                //  а не INTERNAL. Добавить регрессионные тесты.
                                JsonObject command = JsonParser
                                        .parseString(text)
                                        .getAsJsonObject();
                                JsonObject result = service.admissionCommand(command);

                                observer.onNext(
                                        BytesValue.newBuilder().setValue(
                                                ByteString.copyFromUtf8(result.toString())
                                        ).build()
                                );

                                observer.onCompleted();
                            } catch (IllegalArgumentException | JsonParseException e) {
                                observer.onError(
                                        Status.INVALID_ARGUMENT
                                                .withDescription("Admission request rejected")
                                                .asRuntimeException()
                                );
                            } catch (IllegalStateException e) {
                                observer.onError(
                                        Status.INTERNAL
                                                .withDescription("Admission storage unavailable")
                                                .asRuntimeException()
                                );
                            }
                        })
                )
                .build();
    }

    /**
     * <b>Создаёт перехватчик проверки полномочий для RPC-вызовов Frontier.</b>
     *
     * <p>Требует действительный capability-токен для методов
     * Admission/Command, PutURLs и GetURLs. Токен извлекается
     * из заголовка x-onionall-admission-token и сравнивается
     * с серверным значением через {@code MessageDigest.isEqual()}.</p>
     *
     * <p>Разрешает без токена только явно перечисленные методы
     * чтения основной службы URLFrontier. Административные
     * и неизвестные методы отклоняются независимо
     * от наличия правильного токена.</p>
     *
     * <p>Проверка выполняется при открытии RPC-вызова.
     * Перехватчик не реализует отзыв полномочий
     * внутри уже открытого потока и не заменяет
     * предметные проверки службы RocksDB.</p>
     *
     * @param token серверный capability-токен
     *              длиной от 32 до 256 символов
     * @return перехватчик, применяющий политику доступа
     * @throws IllegalArgumentException если токен отсутствует
     *                                  или имеет недопустимую длину
     */
    public static ServerInterceptor guard(String token) {
        // TODO(security): Определить допустимую кодировку
        //  и формат capability-токена для ASCII-заголовка gRPC.
        //  Согласовать проверку длины с фактическим представлением
        //  токена и добавить тесты для недопустимых символов.
        if (
                token==null ||
                token.length() < 32 ||
                token.length() > 256
        ) throw new IllegalArgumentException("Require ONIONALL_ADMISSION_TOKEN32..256");

        return new ServerInterceptor() {
            // TODO(security): Зафиксировать требования к отзыву
            //  полномочий у уже открытых потоковых RPC.
            //  Если необходим немедленный отзыв токена,
            //  предусмотреть завершение активных потоков
            //  и проверить отсутствие последующих изменений состояния.

            /**
             * <b>Проверяет право клиента на выполнение входящего RPC-вызова.</b>
             *
             * <p>Определяет вызываемый метод и применяет соответствующее
             * правило доступа. Для защищённых операций проверяет токен,
             * для разрешённых чтений проверяет принадлежность основной
             * службе URLFrontier, остальные вызовы отклоняет.</p>
             *
             * <p>При отказе закрывает вызов со статусом {@code PERMISSION_DENIED}
             * и не передаёт его обработчику. При успешной проверке
             * делегирует выполнение следующему звену обработки.</p>
             *
             * @param call входящий RPC-вызов
             * @param headers метаданные запроса, включая токен
             * @param next следующий обработчик вызова
             * @param <Q> тип сообщения запроса
             * @param <S> тип сообщения ответа
             * @return обработчик событий разрешённого или отклонённого вызова
             */
            public <Q, S> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, S> call,
                    Metadata headers,
                    ServerCallHandler<Q, S> next
            ) {
                String method = call.getMethodDescriptor().getFullMethodName();

                if (CAPABILITY_METHODS.contains(method)){
                    String offered = headers.get(
                            Metadata.Key.of(
                                    "x-onionall-admission-token",
                                    Metadata.ASCII_STRING_MARSHALLER
                            )
                    );

                    if (offered == null ||
                            !MessageDigest.isEqual(
                                    token.getBytes(StandardCharsets.UTF_8),
                                    offered.getBytes(StandardCharsets.UTF_8))
                    ) {
                        call.close(
                                Status.PERMISSION_DENIED
                                        .withDescription("Frontier capability required"),
                                new Metadata()
                        );
                        return new ServerCall.Listener<>() {
                        };
                    }

                    // Для методов без обязательного токена действует
                    // строгий список разрешённых чтений.
                    // Правильный токен не расширяет полномочия
                    // до административных операций.
                } else {
                    String shortName = call.getMethodDescriptor().getBareMethodName();
                    // GetURLs acquires leases and PutURLs mutates scheduling: both are
                    // authenticated above for every crawl ID. Only real legacy reads
                    // remain public. Admin/unknown methods stay denied even with a token.
                    if (
                            !method.startsWith(URLFrontierGrpc.SERVICE_NAME+"/") ||
                            !READ_METHODS.contains(shortName)
                    ) {
                        call.close(
                                Status.PERMISSION_DENIED
                                        .withDescription("Administrative mutation disabled on admission candidate"),
                                new Metadata()
                        );
                        return new ServerCall.Listener<>() {
                        };
                    }
                }
                return next.startCall(call,headers);
            }
        };
    }
}
