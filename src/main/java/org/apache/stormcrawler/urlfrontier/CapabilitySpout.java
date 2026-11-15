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
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_CRAWL_ID_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_DEFAULT_HOST;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_DEFAULT_PORT;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_DELAY_REQUESTABLE_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_HOST_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_MAX_BUCKETS_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_MAX_URLS_PER_BUCKET_DEFAULT;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_MAX_URLS_PER_BUCKET_KEY;
import static org.apache.stormcrawler.urlfrontier.Constants.URLFRONTIER_PORT_KEY;

import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.URLFrontierGrpc.URLFrontierStub;
import crawlercommons.urlfrontier.Urlfrontier.GetParams;
import crawlercommons.urlfrontier.Urlfrontier.URLInfo;
import io.grpc.ManagedChannel;
import dev.onionall.brawler.admission.FrontierCapability;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.storm.Config;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.AbstractQueryingSpout;
import org.apache.stormcrawler.util.ConfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Получает URL из защищённого URLFrontier для StormCrawler.</b>
 *
 * <p>Клиентский Spout-адаптер, обеспечивающий интеграцию StormCrawler
 * с сервером URLFrontier через асинхронный gRPC server-streaming RPC.
 *
 * <p>При инициализации создаёт аутентифицированный gRPC-клиент,
 * загружает параметры получения URL и настраивает подключение
 * к соответствующему экземпляру Frontier.
 *
 * <p>Метод {@code populateBuffer()} запрашивает доступные URL
 * посредством {@code GetURLs}, преобразует полученные метаданные
 * в формат StormCrawler и помещает результаты во внутренний буфер
 * родительского {@link AbstractQueryingSpout}.
 *
 * <p>Планирование обхода, reservations и управление leases
 * остаются ответственностью серверной части URLFrontier.
 * Сам класс не выполняет HTTP-запросы и не занимается
 * архивированием полученного содержимого.
 *
 * <p>Основан на StormCrawler 3.7.0 с изменениями Onionall:
 * обязательная capability-аутентификация и дополнительная
 * обработка жизненного цикла gRPC-ресурсов.
 *
 * <p>Upstream source SHA256:
 * {@code 031aa003910552f9327785cf378beb327f213f691dc40b688187c3d1864ecd91}.
 *
 * @Reviewer: Stephan Kolesnikov
 * @author GPT-6.1 Sol Max
 */

public class CapabilitySpout extends AbstractQueryingSpout {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilitySpout.class);

    /** gRPC-соединение с Frontier Server */
    private ManagedChannel channel;

    private boolean closed, opened;

    /** Учётные данные для аутентификации (не сериализуются стандартным Java-образом) */
    private transient FrontierCapability capability;

    /** gRPC-клиент для обращения к серверу */
    private URLFrontierStub frontier;

    /** Максимум URL на очередь при запросе */
    private int maxURLsPerBucket;

    /** Максимальное число запрашиваемых очередей */
    private int maxBucketNum;

    /** Параметр доступности URL для выдачи */
    private int delayRequestable;

    /** Идентификатор конкретного crawl, если задан */
    private String globalCrawlID = null;

    /**
     * <b>Инициализирует Spout и подключение к URLFrontier.</b>
     *
     * <p>Загружает FrontierCapability и параметры конфигурации,
     * инициализирует родительский компонент, выбирает адрес Frontier
     * и создаёт аутентифицированный gRPC stub.
     *
     * <p>При нескольких адресах выполняет детерминированное
     * сопоставление Storm tasks с экземплярами Frontier.
     * При ошибке инициализации запускает очистку уже созданных ресурсов.
     *
     * @param stormConf конфигурация Storm и параметры подключения к Frontier
     * @param context контекст текущей задачи Storm
     * @param collector получатель исходящих tuples
     * @throws IllegalStateException если экземпляр уже закрыт
     * @throws RuntimeException если инициализация завершилась ошибкой
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public synchronized void open(
            Map<String, Object> stormConf,
            TopologyContext context,
            SpoutOutputCollector collector
    ) {
        if (closed) {
            throw new IllegalStateException("Frontier spout closed");
        }
        try {
            capability = FrontierCapability.from(stormConf);
            opened = true;
            super.open(stormConf, context, collector);

            maxURLsPerBucket = ConfUtils.getInt(
                    stormConf,
                    URLFRONTIER_MAX_URLS_PER_BUCKET_KEY,
                    URLFRONTIER_MAX_URLS_PER_BUCKET_DEFAULT
            );

            maxBucketNum = ConfUtils.getInt(
                    stormConf,
                    URLFRONTIER_MAX_BUCKETS_KEY,
                    10
            );

            delayRequestable = ConfUtils.getInt(
                    stormConf,
                    Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS,
                    delayRequestable
            );

            delayRequestable = ConfUtils.getInt(
                    stormConf,
                    URLFRONTIER_DELAY_REQUESTABLE_KEY,
                    delayRequestable
            );

            List<String> addresses = ConfUtils.loadListFromConf(
                    URLFRONTIER_ADDRESS_KEY,
                    stormConf
            );

            String address;
            switch (addresses.size()) {
                case 0:
                    LOG.debug("{} has no addresses.", URLFRONTIER_ADDRESS_KEY);
                    address = null;
                    break;
                case 1:
                    LOG.debug("{} with a size of {} is used.", URLFRONTIER_ADDRESS_KEY, addresses.size());
                    address = addresses.getFirst();
                    break;
                default:
                    int totalTasks = context.getComponentTasks(context.getThisComponentId()).size();
                    if (totalTasks != addresses.size()) {
                        String message = "Not one task per frontier node. " + totalTasks + " vs " + addresses.size();
                        LOG.error(message);
                        throw new RuntimeException(message);
                    }
                    int nodeIndex = context.getThisTaskIndex();
                    Collections.sort(addresses);
                    address = addresses.get(nodeIndex);
            }

            if (address == null) {
                channel = ManagedChannelUtil.createChannel(
                        ConfUtils.getString(
                                stormConf,
                                URLFRONTIER_HOST_KEY,
                                URLFRONTIER_DEFAULT_HOST
                        ),
                        ConfUtils.getInt(
                                stormConf,
                                URLFRONTIER_PORT_KEY,
                                URLFRONTIER_DEFAULT_PORT
                        )
                );
            } else {
                channel = ManagedChannelUtil.createChannel(address);
            }

            // Создаётся асинхронный gRPC-клиент,
            // затем подключается аутентификация,
            // и потом разрешается RPC ожидать готовность канала, вместо немедленного отказа
            frontier = URLFrontierGrpc
                    .newStub(channel)
                    .withCallCredentials(capability)
                    .withWaitForReady();

            LOG.debug("State of Channel: {}", channel.getState(false));
            globalCrawlID = ConfUtils.getString(
                    stormConf,
                    URLFRONTIER_CRAWL_ID_KEY
            );

            if (globalCrawlID != null) {
                LOG.info("Initialized URLFrontier CapabilitySpout for crawlId {}", globalCrawlID);
            } else {
                LOG.info("Initialized URLFrontier CapabilitySpout without crawlId");
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }


    /**
     * <b>Запрашивает URL из Frontier и заполняет внутренний буфер.</b>
     *
     * <p>Формирует GetParams с ограничениями количества очередей,
     * URL на очередь и параметрами доступности заданий.
     *
     * <p>Выполняет асинхронный server-streaming RPC GetURLs.
     * Полученные URLInfo преобразуются в URL и Metadata StormCrawler,
     * после чего помещаются во внутренний буфер родительского класса.
     *
     * <p>Завершение запроса и ошибки транспорта передаются
     * механизму управления запросами AbstractQueryingSpout.
     *
     * @Reviewer: Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override protected void populateBuffer() {
        LOG.debug(
                "Populating buffer - max queues {} - max URLs per queues {}",
                maxBucketNum,
                maxURLsPerBucket);

        GetParams.Builder builder =
                GetParams.newBuilder()
                        .setMaxUrlsPerQueue(maxURLsPerBucket)
                        .setDelayRequestable(delayRequestable)
                        .setMaxQueues(maxBucketNum);

        if (globalCrawlID != null) {
            builder.setCrawlID(globalCrawlID);
        }

        GetParams request = builder.build();

        final AtomicInteger counter = new AtomicInteger();
        final long start = System.currentTimeMillis();

        StreamObserver<URLInfo> responseObserver =
                new StreamObserver<>() {

                    @Override
                    public void onNext(URLInfo item) {
                        Metadata m = new Metadata();
                        item.getMetadataMap()
                                .forEach(
                                        (k, v) -> {
                                            for (int index = 0;
                                                    index < v.getValuesCount();
                                                    index++) {
                                                m.addValue(k, v.getValues(index));
                                            }
                                        });
                        buffer.add(item.getUrl(), m, item.getKey());
                        counter.addAndGet(1);
                    }

                    @Override
                    public void onError(Throwable t) {

                        if (t instanceof io.grpc.StatusRuntimeException e) {
                            StringBuilder sb =
                                    new StringBuilder("StatusRuntimeException: ")
                                            .append(e.getStatus().toString());

                            io.grpc.Metadata trailers = e.getTrailers();
                            if (trailers != null) {
                                sb.append(" ").append(trailers);
                            }
                            LOG.error(sb.toString(), e);
                        } else {
                            LOG.error("Exception caught: ", t);
                        }

                        markQueryReceivedNow();
                    }

                    @Override
                    public void onCompleted() {
                        final long end = System.currentTimeMillis();
                        LOG.debug(
                                "Got {} URLs from the frontier in {} msec",
                                counter.get(),
                                (end - start));
                        markQueryReceivedNow();
                    }
                };

        LOG.trace("isInquery set to true");
        isInQuery.set(true);

        frontier.getURLs(request, responseObserver);
    }


    /**
     * <b>Подтверждает успешную обработку tuple.</b>
     *
     * <p>Записывает событие подтверждения в лог и делегирует
     * обработку родительскому AbstractQueryingSpout.
     *
     * @param msgId идентификатор обработанного Storm tuple
     * @Reviewer:  Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public void ack(Object msgId) {
        LOG.debug("Ack for {}", msgId);
        super.ack(msgId);
    }

    /**
     * <b>Сообщает о неуспешной обработке tuple.</b>
     *
     * <p>Записывает событие ошибки в лог и делегирует
     * обработку родительскому AbstractQueryingSpout.
     *
     * @param msgId идентификатор tuple, обработка которого завершилась ошибкой
     * @Reviewer:  Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override
    public void fail(Object msgId) {
        LOG.info("Fail for {}", msgId);
        super.fail(msgId);
    }

    /**
     * <b>Завершает работу Spout и освобождает gRPC-ресурсы.</b>
     *
     * <p>Выполняет идемпотентное закрытие родительского компонента
     * и принадлежащего экземпляру ManagedChannel.
     *
     * <p>Закрытие канала выполняется в блоке finally,
     * поэтому освобождение транспортного ресурса предпринимается
     * даже при исключении во время очистки родительского компонента.
     *
     * @Reviewer:  Stephan Kolesnikov
     * @author GPT-6.1 Sol Max
     */
    @Override public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (opened) {
                super.close();
            }
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
        }
    }
}
