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
import crawlercommons.urlfrontier.URLFrontierGrpc.URLFrontierStub;
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

/** Source-pinned StormCrawler 3.7.0 StatusUpdaterBolt adapter.
 * Upstream source SHA256: de66cdf9184c8283d9a944bee57bd681d1863237f64eb4caa9b30951811b2558.
 * Onionall deviations include capability credentials and lifecycle hardening; see package AGENTS.md.
 */
public class CapabilityStatusUpdaterBolt extends AbstractStatusUpdaterBolt
        implements RemovalListener<String, List<Tuple>>,
                StreamObserver<crawlercommons.urlfrontier.Urlfrontier.AckMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityStatusUpdaterBolt.class);

    // streamLock serializes asynchronous stream creation, writes and terminal callbacks.
    // Lock order: streamLock -> waitAckLock. Eviction takes only waitAckLock.
    private final Object streamLock = new Object();
    private boolean closed, authorizationFailed;
    private long streamGeneration;
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Integer> pendingUrls = new HashMap<>();
    private record Pending(String url, Tuple tuple) { }

    private ManagedChannel channel;
    private transient FrontierCapability capability;
    private URLPartitioner partitioner;
    private StreamObserver<URLItem> requestObserver;

    private Cache<String, List<Tuple>> waitAck;

    // We have to prevent starving caused by the cache-timeout. Therefore, sophisticated lock with
    // fairness.
    private final ReentrantLock waitAckLock = new ReentrantLock(true);

    private int maxMessagesInFlight = 100000;
    private long throttleTimeMS;

    // Faster ways of locking until n messages are processed
    private Semaphore inFlightSemaphore;

    private ScopedCounter eventCounter;

    /** Globally set crawlID * */
    private String globalCrawlID;

    /**
     * <b>Настраивает authenticated stream и bounded pending state.</b>
     *
     * <p>Инициализация сериализована с cleanup; при ошибке освобождает ресурсы и сохраняет исходную причину.
     */
    @Override
    public void prepare(Map<String, Object> stormConf, TopologyContext context, OutputCollector collector) {
        synchronized (streamLock) {
            if (closed) {
                throw new IllegalStateException("Frontier updater closed");
            }
            try {
                capability = FrontierCapability.from(stormConf);
                super.prepare(stormConf, context, collector);
                var expireAfterNMillisec = ConfUtils.getLong(stormConf, URLFRONTIER_CACHE_EXPIREAFTER_SEC_KEY, 60);
                waitAck = Caffeine.newBuilder().expireAfterWrite(expireAfterNMillisec,
                    TimeUnit.SECONDS).removalListener(this).build();
                maxMessagesInFlight = ConfUtils.getInt(stormConf, URLFRONTIER_MAX_MESSAGES_IN_FLIGHT_KEY,
                    maxMessagesInFlight);
                throttleTimeMS = ConfUtils.getLong(stormConf, URLFRONTIER_THROTTLING_TIME_MS_KEY, 10);
                eventCounter = CrawlerMetrics.registerCounter(context, stormConf, this.getClass().getSimpleName(), 30);
                maxMessagesInFlight = ConfUtils.getInt(stormConf, URLFRONTIER_UPDATER_MAX_MESSAGES_KEY,
                    maxMessagesInFlight);
                LOG.info("Allowing up to {} message(s) in flight", maxMessagesInFlight);
                // Fairness not necessary, we are not in a hurry, as long as we may be processed at some
                // point.
                inFlightSemaphore = new Semaphore(maxMessagesInFlight, false);
                partitioner = new URLPartitioner();
                partitioner.configure(stormConf);
                globalCrawlID = ConfUtils.getString(stormConf, URLFRONTIER_CRAWL_ID_KEY, CrawlID.DEFAULT);
                // host and port of URL Frontier(s)
                List<String> addresses = ConfUtils.loadListFromConf(URLFRONTIER_ADDRESS_KEY, stormConf);
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
                    String host = ConfUtils.getString(stormConf, URLFRONTIER_HOST_KEY, URLFRONTIER_DEFAULT_HOST);
                    int port = ConfUtils.getInt(stormConf, URLFRONTIER_PORT_KEY, URLFRONTIER_DEFAULT_PORT);
                    address = host + ":" + port;
                }
                channel = ManagedChannelUtil.createChannel(address);
                channel.notifyWhenStateChanged(ConnectivityState.SHUTDOWN,
                    () -> onChannelStateChange(ConnectivityState.SHUTDOWN));
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
     * <b>Создаёт одну authenticated RPC generation под streamLock.</b>
     *
     * <p>Повтор выполняет следующая отправка или изменение состояния канала;
     * фонового reconnect-цикла и повторной отправки неподтверждённых сообщений нет.
     */
    private void openStream() {
        if (closed || authorizationFailed || requestObserver != null) {
            return;
        }
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
        var created = URLFrontierGrpc.newStub(channel).withCallCredentials(capability).withWaitForReady().putURLs(observer);
        if (!closed && generation == streamGeneration) {
            requestObserver = created;
        } else {
            created.onCompleted();
        }
    }

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
            channel.notifyWhenStateChanged(next, () -> onChannelStateChange(next));
        }
    }

    /**
     * <b>Завершает только конкретную отправку; поздний ACK не затрагивает новую.</b>
     */
    private void finish(String id, boolean success) {
        Pending value;
        waitAckLock.lock();
        try {
            value = pending.remove(id);
            if (value == null) {
                return;
            }
            waitAck.invalidate(id);
            pendingUrls.compute(value.url(), (url, count) -> count == 1 ? null : count - 1);
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
     * <b>Применяет ACK только к существующему уникальному request ID.</b>
     *
     * <p>Повторный либо поздний ACK не освобождает permit и не затрагивает новую отправку.
     */
    @Override
    public void onNext(AckMessage confirmation) {
        finish(confirmation.getID(), confirmation.getStatus() != AckMessage.Status.FAIL);
    }

    /**
     * <b>Завершает текущую stream generation и fail-ит pending.</b>
     *
     * <p>Auth-отказ блокирует отправки до transport recovery или нового экземпляра; anonymous fallback отсутствует.
     */
    @Override
    public void onError(Throwable error) {
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
     * <b>Завершает generation без подтверждения pending tuples.</b>
     *
     * <p>Следующая отправка создаёт новый authenticated stream; фонового reconnect-цикла нет.
     */
    @Override
    public void onCompleted() {
        synchronized (streamLock) {
            requestObserver = null;
            ++streamGeneration;
            failPending();
        }
    }

    /**
     * <b>Отправляет один status с уникальным request ID.</b>
     *
     * <p>Interrupt fail-ит tuple и сохраняет флаг. Неподтверждённые сообщения при stream failure fail-ятся для Storm replay.
     */
    @Override
    public void store(@NotNull String url, @NotNull Status status, @NotNull Metadata metadata,
        @NotNull Optional<Date> nextFetch, @NotNull Tuple t) {
        if (inFlightSemaphore == null) {
            throw new IllegalStateException("Frontier updater not prepared");
        }
        try {
            while (!inFlightSemaphore.tryAcquire(throttleTimeMS, TimeUnit.MILLISECONDS)) {
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
     * <b>Fail-ит отправку при eviction.</b>
     *
     * <p>Удаление из authoritative pending map выбирает единственного владельца ACK/fail и permit release.
     */
    @Override
    public void onRemoval(@Nullable String key, @Nullable List<Tuple> values, @NotNull RemovalCause cause) {
        if (cause.wasEvicted()) {
            finish(key, false);
        }
    }

    /**
     * <b>Идемпотентно закрывает поток и канал, fail-ит pending tuples.</b>
     *
     * <p>Закрытое состояние запрещает reconnect даже при запоздалых callbacks.
     */
    @Override
    public void cleanup() {
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
}
