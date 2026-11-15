/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.stormcrawler.urlfrontier;

import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_ADDRESS_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_CACHE_EXPIREAFTER_SEC_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_CRAWL_ID_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_DEFAULT_HOST;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_DEFAULT_PORT;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_HOST_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_MAX_MESSAGES_IN_FLIGHT_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_PORT_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_THROTTLING_TIME_MS_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_UPDATER_MAX_MESSAGES_KEY;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import crawlercommons.urlfrontier.CrawlID;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.Urlfrontier.AckMessage;
import crawlercommons.urlfrontier.Urlfrontier.DiscoveredURLItem;
import crawlercommons.urlfrontier.Urlfrontier.KnownURLItem;
import crawlercommons.urlfrontier.Urlfrontier.StringList;
import crawlercommons.urlfrontier.Urlfrontier.StringList.Builder;
import crawlercommons.urlfrontier.Urlfrontier.URLInfo;
import crawlercommons.urlfrontier.Urlfrontier.URLItem;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import dev.onionall.brawler.admission.FrontierCapability;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.Serial;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.metrics.CrawlerMetrics;
import org.apache.stormcrawler.metrics.ScopedCounter;
import org.apache.stormcrawler.persistence.AbstractStatusUpdaterBolt;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.util.ConfUtils;
import org.apache.stormcrawler.util.URLPartitioner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <b>Отправляет статусы обработки URL в защищённый URLFrontier.</b>
 *
 * <p>Адаптер StormCrawler, расширяющий AbstractStatusUpdaterBolt
 * для асинхронной передачи информации об обработанных и обнаруженных
 * URL через двусторонний gRPC stream PutURLs.
 *
 * <p>Выполняет преобразование URL, статусов, метаданных и параметров
 * следующего посещения в protobuf-представление URLFrontier.
 *
 * <p>Обеспечивает контроль незавершённых отправок, корреляцию
 * подтверждений по уникальным UUID, ограничение конкурентности
 * посредством Semaphore и обработку истечения времени ожидания
 * через Caffeine Cache.
 *
 * <p>Использует generation-aware обработку gRPC callbacks,
 * capability-аутентификацию и восстановление соединения
 * при изменении состояния транспортного канала.
 *
 * <p>Планирование обхода и reservations остаются ответственностью
 * серверной части URLFrontier. Класс не выполняет загрузку
 * содержимого страниц и не занимается архивированием HTML.
 *
 * <p>Основан на StormCrawler 3.7.0 с изменениями Onionall,
 * включающими обязательную аутентификацию, уникальные request ID,
 * защиту от повторного завершения pending-операций и расширенную
 * обработку ошибок и жизненного цикла gRPC.
 *
 * <p>Upstream source SHA256:
 * {@code de66cdf9184c8283d9a944bee57bd681d1863237f64eb4caa9b30951811b2558}.
 *
 * @Reviewer: Stephan Kolesnikov
 * @author GPT-6.1 Sol Max
 */

public class CapabilityStatusUpdaterBolt extends AbstractStatusUpdaterBolt
        implements RemovalListener<String, List<Tuple>>,
                StreamObserver<crawlercommons.urlfrontier.Urlfrontier.AckMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityStatusUpdaterBolt.class);

    private record Pending(String url, Tuple tuple) { }

    /**
     * Главный монитор синхронизации, защищающий создание gRPC-потока,
     * отправку сообщений,
     * обработку callback-ов и завершение соединения.
     * */
    private transient final Object streamLock;

    private boolean closed, authorizationFailed;

    /**
     * Идентифицирует конкретное поколение gRPC-потока.
     * */
    private long streamGeneration;

    /**
     * Хранит незавершённые отправки.
     * <p>
     *     Ключ — уникальный идентификатор запроса.
     * </p>
     *
     * <p>
     *     Значение — URL и соответствующий Storm tuple.
     * </p>
     * */
    private final Map<String, Pending> pending = new HashMap<>();

    /**
     * Счётчик незавершённых отправок для каждого URL.
     * */
    private final Map<String, Integer> pendingUrls = new HashMap<>();

    /** gRPC-соединение */
    private ManagedChannel channel;

    /** Полномочия для аутентификации */
    private transient FrontierCapability capability;

    /** Определение очереди для URL */
    private URLPartitioner partitioner;

    /** Отправка URLItem в Frontier */
    private StreamObserver<URLItem> requestObserver;

    /** Caffeine Cache для контроля времени ожидания ACK */
    private Cache<String, List<Tuple>> waitAck;

    /** Защита состояния незавершённых отправок */
    private final ReentrantLock waitAckLock = new ReentrantLock(true);

    /** Максимальное количество незавершённых отправок */
    private int maxMessagesInFlight = 100000;

    /** Интервал ожидания свободного permit */
    private long throttleTimeMS;

    /** Ограничение числа одновременно отправленных сообщений */
    private Semaphore inFlightSemaphore;

    /** Метрики успешных и неуспешных отправок */
    private ScopedCounter eventCounter;

    /** Идентификатор обхода */
    private String globalCrawlID;

    /**
     * <b>Восстанавливает служебный монитор после десериализации.</b>
     *
     * <p>Восстанавливает сериализуемое состояние компонента
     * и создаёт новый worker-local монитор синхронизации,
     * который не переносится между JVM посредством сериализации.
     *
     * @param input поток входящей Java-десериализации
     * @throws IOException ошибка чтения сериализованного состояния
     * @throws ClassNotFoundException класс сериализуемого объекта не найден
     * <p><b>@Reviewer: Stephan Kolesnikov</b></p>
     * @author GPT-6.1 Sol Max
     */
    @Serial private void readObject(
            java.io.ObjectInputStream input
    ) throws java.io.IOException, ClassNotFoundException {
        input.defaultReadObject();
    }


    /**
     * <b>Инициализирует адаптер и подключение к URLFrontier.</b>
     *
     * <p>Загружает capability credentials, настраивает родительский
     * StatusUpdaterBolt, создаёт Caffeine Cache и Semaphore,
     * регистрирует метрики и конфигурирует URLPartitioner.
     *
     * <p>Определяет адрес Frontier с учётом распределения задач Storm,
     * создаёт ManagedChannel и открывает аутентифицированный
     * двусторонний gRPC stream PutURLs.
     *
     * <p>При ошибке инициализации выполняет очистку созданных ресурсов,
     * сохраняя исходное исключение и добавляя ошибки очистки
     * в suppressed exceptions.
     *
     * @param stormConf конфигурация Storm и URLFrontier
     * @param context контекст текущей задачи Storm
     * @param collector получатель подтверждений и ошибок tuple
     * @throws IllegalStateException если экземпляр уже закрыт
     * @throws RuntimeException если инициализация завершилась ошибкой
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void prepare(
            Map<String, Object> stormConf,
            TopologyContext context,
            OutputCollector collector
    ) {
        synchronized (streamLock) {
            if (closed) {
                throw new IllegalStateException("Frontier updater closed");
            }
            try {
                capability = FrontierCapability.from(stormConf);
                super.prepare(stormConf, context, collector);

                var expireAfterNMillis = ConfUtils.getLong(
                        stormConf,
                        URLFRONTIER_CACHE_EXPIREAFTER_SEC_KEY,
                        60
                );

                waitAck = Caffeine
                            .newBuilder()
                            .expireAfterWrite(
                                expireAfterNMillis,
                                TimeUnit.SECONDS
                            )
                            .removalListener(this)
                            .build();

                maxMessagesInFlight = ConfUtils.getInt(
                        stormConf,
                        URLFRONTIER_MAX_MESSAGES_IN_FLIGHT_KEY,
                        maxMessagesInFlight
                );
                throttleTimeMS = ConfUtils.getLong(
                        stormConf,
                        URLFRONTIER_THROTTLING_TIME_MS_KEY,
                        10
                );
                eventCounter = CrawlerMetrics.registerCounter(
                        context,
                        stormConf,
                        this.getClass().getSimpleName(),
                        30
                );
                maxMessagesInFlight = ConfUtils.getInt(
                        stormConf,
                        URLFRONTIER_UPDATER_MAX_MESSAGES_KEY,
                        maxMessagesInFlight
                );

                LOG.info("Allowing up to {} message(s) in flight", maxMessagesInFlight);

                // Fairness not necessary, we are not in a hurry, as long as we may be processed at some
                // point.
                inFlightSemaphore = new Semaphore(maxMessagesInFlight, false);

                partitioner = new URLPartitioner();
                partitioner.configure(stormConf);

                globalCrawlID = ConfUtils.getString(
                        stormConf,
                        URLFRONTIER_CRAWL_ID_KEY,
                        CrawlID.DEFAULT
                );

                // host and port of URL Frontier(s)
                List<String> addresses = ConfUtils.loadListFromConf(
                        URLFRONTIER_ADDRESS_KEY,
                        stormConf
                );

                // Selected address
                String address;
                switch(addresses.size()) {
                    case 0:
                        LOG.debug("{} has no addresses.", URLFRONTIER_ADDRESS_KEY);
                        address = null;
                        break;
                    case 1:
                        LOG.debug("{} with a size of {} is used.", URLFRONTIER_ADDRESS_KEY, addresses.size());
                        address = addresses.get(0);
                        break;
                    default:
                        LOG.debug("{} with a size of {} is used.", URLFRONTIER_ADDRESS_KEY, addresses.size());
                        int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();
                        // check that the number of tasks is a multiple of the frontier nodes
                        if (totalTasks < addresses.size()) {
                            String message = "Needs at least one task per frontier node. " + totalTasks + " vs " + addresses.size();
                            LOG.error(message);
                            throw new RuntimeException(message);
                        }
                        if (totalTasks % addresses.size() != 0) {
                            String message = "Number of tasks not a multiple of the number of frontier nodes. " + totalTasks + " vs " + addresses.size();
                            LOG.error(message);
                            throw new RuntimeException(message);
                        }
                        int nodeIndex = context.getThisTaskIndex();
                        int assignment = nodeIndex % addresses.size();
                        Collections.sort(addresses);
                        address = addresses.get(assignment);
                }
                if (address == null) {
                    String host = ConfUtils.getString(
                            stormConf,
                            URLFRONTIER_HOST_KEY,
                            URLFRONTIER_DEFAULT_HOST
                    );
                    int port = ConfUtils.getInt(
                            stormConf,
                            URLFRONTIER_PORT_KEY,
                            URLFRONTIER_DEFAULT_PORT
                    );
                    address = host + ":" + port;
                }
                channel = ManagedChannelUtil.createChannel(address);
                channel.notifyWhenStateChanged(
                        ConnectivityState.SHUTDOWN,
                        () -> onChannelStateChange(ConnectivityState.SHUTDOWN)
                );
                synchronized (streamLock) {
                    openStream();
                }
            } catch (RuntimeException | Error failure) {
                try {
                    cleanup();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }


    /**
     * <b>Создаёт аутентифицированный двусторонний gRPC stream.</b>
     *
     * <p>Открывает PutURLs через URLFrontierGrpc, регистрирует
     * обработчик входящих AckMessage и сохраняет StreamObserver
     * для отправки URLItem.
     *
     * <p>Каждое открытие получает собственный идентификатор поколения.
     * Callbacks устаревших потоков игнорируются, чтобы предотвратить
     * изменение состояния актуального соединения.
     *
     * <p>Повторное открытие не выполняется при закрытом компоненте,
     * блокировке авторизации или наличии уже активного потока.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    private void openStream() {
        if (closed || authorizationFailed || requestObserver != null) return;

        long generation = ++streamGeneration;
        var observer = new StreamObserver<AckMessage>() {

            public void onNext(AckMessage ack) {
                synchronized (streamLock) {
                    if (!closed && generation == streamGeneration) {
                        CapabilityStatusUpdaterBolt.this.onNext(ack);
                    }
                }
            }

            public void onError(Throwable error) {
                synchronized (streamLock) {
                    if (!closed && generation == streamGeneration) {
                        CapabilityStatusUpdaterBolt.this.onError(error);
                    }
                }
            }

            public void onCompleted() {
                synchronized (streamLock) {
                    if (!closed && generation == streamGeneration) {
                        CapabilityStatusUpdaterBolt.this.onCompleted();
                    }
                }
            }
        };
        var created = URLFrontierGrpc
                .newStub(channel)
                .withCallCredentials(capability)
                .withWaitForReady()
                .putURLs(observer);
        if (!closed && generation == streamGeneration) {
            requestObserver = created;
        } else {
            created.onCompleted();
        }
    }


    /**
     * <b>Обрабатывает изменения состояния gRPC-канала.</b>
     *
     * <p>Отслеживает состояние ManagedChannel и регистрирует
     * последующие уведомления об изменениях транспортного состояния.
     *
     * <p>При восстановлении канала после TRANSIENT_FAILURE
     * снимает блокировку авторизации и инициирует открытие
     * новой generation gRPC stream.
     *
     * <p>Не создаёт самостоятельный фоновый цикл переподключения.
     *
     * @param state предыдущее состояние канала, вызвавшее callback
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    private void onChannelStateChange(ConnectivityState state) {
        synchronized (streamLock) {
            if (closed || channel == null || channel.isShutdown()) {
                return;
            }
            ConnectivityState next = channel.getState(true);
            if (state == ConnectivityState.TRANSIENT_FAILURE) {
                authorizationFailed = false;
                openStream();
            }
            channel.notifyWhenStateChanged(
                    next,
                    () -> onChannelStateChange(next)
            );
        }
    }

    /**
     * <b>Завершает конкретную незавершённую отправку URL.</b>
     *
     * <p>Находит pending-операцию по уникальному request ID,
     * удаляет её из реестра, инвалидирует запись Caffeine Cache,
     * обновляет счётчик URL и освобождает один Semaphore permit.
     *
     * <p>При успешном завершении подтверждает исходный tuple
     * через родительский StatusUpdaterBolt.
     *
     * <p>При ошибке сообщает Storm о неуспешной обработке tuple.
     *
     * <p>Повторное завершение, дублирующий ACK или позднее
     * подтверждение не приводят к повторному освобождению permit.
     *
     * @param id уникальный идентификатор отправки
     * @param success признак успешного подтверждения Frontier
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    private void finish(
            String id,
            boolean success
    ) {
        Pending value;
        waitAckLock.lock();
        try {
            value = pending.remove(id);
            if (value == null) {
                return;
            }
            waitAck.invalidate(id);
            pendingUrls.computeIfPresent(
                    value.url(),
                    (_, count) ->
                            count.equals(1) ? null : count - 1
            );
            inFlightSemaphore.release();
        } finally {
            waitAckLock.unlock();
        }
        if (success) {
            eventCounter.scope("acked").incrBy(1);
            super.ack(value.tuple(), value.url());
        } else {
            eventCounter.scope("failed").incrBy(1);
            collector.fail(value.tuple());
        }
    }

    /**
     * <b>Завершает ошибкой все незавершённые отправки.</b>
     *
     * <p>Создаёт снимок идентификаторов pending-операций
     * и последовательно завершает каждую через finish().
     *
     * <p>Ошибки отдельных операций не прерывают обработку
     * оставшихся отправок. Возникшие исключения агрегируются
     * посредством suppressed exceptions.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    private void failPending() {
        List<String> ids;
        waitAckLock.lock();
        try {
            ids = new ArrayList<>(pending.keySet());
        } finally {
            waitAckLock.unlock();
        }
        RuntimeException failure = null;
        for (String id : ids) {
            try {
                finish(id, false);
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }


    /**
     * <b>Обрабатывает подтверждение URLFrontier.</b>
     *
     * <p>Извлекает уникальный request ID из AckMessage
     * и передаёт результат подтверждения в finish().
     *
     * <p>Связывает ответ сервера с исходным Storm tuple
     * без поиска по URL.
     *
     * @param confirmation сообщение подтверждения от Frontier
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void onNext(AckMessage confirmation) {
        finish(
                confirmation.getID(),
                confirmation.getStatus() != AckMessage.Status.FAIL
        );
    }


    /**
     * <b>Обрабатывает ошибку двустороннего gRPC stream.</b>
     *
     * <p>Определяет статус ошибки, устанавливает блокировку
     * отправок при отказе в авторизации, инвалидирует текущую
     * generation и завершает все pending-операции ошибкой.
     *
     * <p>Неподтверждённые tuple возвращаются механизму
     * повторной доставки Storm. Анонимный fallback отсутствует.
     *
     * @param error причина завершения gRPC stream
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void onError(Throwable error) {
        synchronized (streamLock) {
            var code = io.grpc.Status.fromThrowable(error).getCode();
            authorizationFailed = code == io.grpc.Status.Code.PERMISSION_DENIED ||
                code == io.grpc.Status.Code.UNAUTHENTICATED;
            requestObserver = null;
            ++streamGeneration;
            failPending();
            LOG.warn("Frontier stream ended: {}", code);
        }
    }

    /**
     * <b>Обрабатывает штатное завершение gRPC stream.</b>
     *
     * <p>Инвалидирует текущую generation, сбрасывает ссылку
     * на исходящий observer и завершает неподтверждённые
     * операции ошибкой.
     *
     * <p>Штатное завершение потока не считается подтверждением
     * всех ранее отправленных URLItem.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void onCompleted() {
        synchronized (streamLock) {
            requestObserver = null;
            ++streamGeneration;
            failPending();
        }
    }


    /**
     * <b>Отправляет статус обработки URL в URLFrontier.</b>
     *
     * <p>Ограничивает число незавершённых отправок посредством
     * Semaphore, создаёт уникальный request ID и регистрирует
     * операцию в pending state до отправки сообщения.
     *
     * <p>Определяет partition key, публикует информацию
     * в StormCrawler Queue Stream, преобразует Metadata
     * в protobuf и формирует URLInfo.
     *
     * <p>Для новых URL создаёт DiscoveredURLItem.
     * Для известных URL создаёт KnownURLItem с параметром
     * следующего допустимого посещения.
     *
     * <p>Отправляет URLItem через текущий PutURLs stream.
     * Синхронные ошибки отправки приводят к завершению
     * соответствующих pending-операций ошибкой.
     *
     * @param url адрес обрабатываемой страницы
     * @param status статус обработки URL
     * @param metadata метаданные страницы и обнаруженных ссылок
     * @param nextFetch время следующего посещения, если задано
     * @param t исходный Storm tuple
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void store(
            @NotNull String url,
            @NotNull Status status,
            @NotNull Metadata metadata,
            @NotNull Optional<Date> nextFetch,
            @NotNull Tuple t
    ) {
        if (inFlightSemaphore == null) {
            throw new IllegalStateException("Frontier updater not prepared");
        }
        try {
            while (
                    !inFlightSemaphore.tryAcquire(
                            throttleTimeMS,
                            TimeUnit.MILLISECONDS
                    )
            ) {
                waitAck.cleanUp();
                synchronized (streamLock) {
                    if (closed || authorizationFailed) {
                        collector.fail(t);
                        return;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            collector.fail(t);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        synchronized (streamLock) {
            if (closed || authorizationFailed) {
                inFlightSemaphore.release();
                collector.fail(t);
                return;
            }
            try {
                openStream();
            } catch (RuntimeException failure) {
                inFlightSemaphore.release();
                collector.fail(t);
                return;
            }
            if (requestObserver == null) {
                inFlightSemaphore.release();
                collector.fail(t);
                return;
            }
            waitAckLock.lock();
            try {
                if (status == Status.DISCOVERED && pendingUrls.containsKey(url)) {
                    inFlightSemaphore.release();
                    super.ack(t, url);
                    return;
                }
                pending.put(requestId, new Pending(url, t));
                pendingUrls.merge(url, 1, Integer::sum);
                waitAck.put(requestId, List.of(t));
            } finally {
                waitAckLock.unlock();
            }
            try {
                String partitionKey = partitioner.getPartition(url, metadata);
                if (partitionKey == null) {
                    partitionKey = "_DEFAULT_";
                }
                // send a tuple on the queue stream in case a bolt
                // wants to handle it
                super.collector.emit(org.apache.stormcrawler.Constants.QUEUE_STREAM_NAME, new Values(partitionKey,
                    metadata));
                final Map<String, StringList> mdCopy = new HashMap<>(metadata.size());
                for (String k : metadata.keySet()) {
                    String[] vals = metadata.getValues(k);
                    if (vals != null) {
                        Builder builder = StringList.newBuilder();
                        for (String v : vals) {
                            builder.addValues(v);
                        }
                        mdCopy.put(k, builder.build());
                    }
                }
                URLInfo info = URLInfo.newBuilder().setKey(partitionKey).setUrl(url).setCrawlID(globalCrawlID).putAllMetadata(mdCopy).build();
                crawlercommons.urlfrontier.Urlfrontier.URLItem.Builder itemBuilder = URLItem.newBuilder();
                if (status.equals(Status.DISCOVERED)) {
                    itemBuilder.setDiscovered(DiscoveredURLItem.newBuilder().setInfo(info).build());
                } else {
                    // next fetch date
                    long date = 0;
                    if (nextFetch.isPresent()) {
                        date = nextFetch.get().toInstant().getEpochSecond();
                    }
                    itemBuilder.setKnown(KnownURLItem.newBuilder().setInfo(info).setRefetchableFromDate(date).build());
                }
                requestObserver.onNext(itemBuilder.setID(requestId).build());
            } catch (RuntimeException failure) {
                var broken = requestObserver;
                try {
                    onError(failure);
                } finally {
                    if (broken != null) {
                        try {
                            broken.onError(io.grpc.Status.CANCELLED.asRuntimeException());
                        } catch (RuntimeException ignored) {
                            /* Already terminated. */
                        }
                    }
                }
            }
        }
    }


    /**
     * <b>Обрабатывает истечение записей контроля ACK.</b>
     *
     * <p>При eviction записи Caffeine Cache завершает
     * соответствующую pending-операцию ошибкой через finish().
     *
     * <p>Явная инвалидация записи после получения ACK
     * не инициирует повторное завершение операции.
     *
     * @param key идентификатор отправки
     * @param values связанные с отправкой tuple
     * @param cause причина удаления записи
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void onRemoval(
            @Nullable String key,
            @Nullable List<Tuple> values,
            @NotNull RemovalCause cause
    ) {
        if (cause.wasEvicted()) {
            finish(key, false);
        }
    }


    /**
     * <b>Завершает работу адаптера и освобождает ресурсы.</b>
     *
     * <p>Запрещает новые отправки и переподключения,
     * инвалидирует текущую generation, завершает исходящий
     * gRPC stream и переводит все pending-операции в состояние
     * неуспешного завершения.
     *
     * <p>Закрывает ManagedChannel независимо от ошибок
     * предыдущих этапов очистки.
     *
     * <p>Метод идемпотентен. При возникновении нескольких
     * исключений сохраняет первое и добавляет последующие
     * в suppressed exceptions.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void cleanup() {
        synchronized (streamLock) {
            if (closed) {
                return;
            }
            closed = true;
            ++streamGeneration;
            var observer = requestObserver;
            requestObserver = null;
            RuntimeException failure = null;
            try {
                if (observer != null) {
                    observer.onCompleted();
                }
            } catch (RuntimeException error) {
                failure = error;
            }
            try {
                failPending();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            try {
                if (channel != null) {
                    channel.shutdownNow();
                }
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public CapabilityStatusUpdaterBolt() { this.streamLock = new Object() {
        @Override public String toString() {
            return "CAPABILITY_STATUS_UPDATER_BOLT_LOCK_OBJECT";
        }
    }; }
}
