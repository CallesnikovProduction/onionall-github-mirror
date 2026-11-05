package dev.onionall.brawler.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.apache.stormcrawler.urlfrontier.CapabilityStatusUpdaterBolt;

/**
 * <b>Архивный status updater для защищённого Frontier.</b>
 *
 * <p>Сохраняет наблюдение до capability-aware PutURLs; stock-вариант остаётся отдельным fixture-компонентом.
 */
public final class CapabilityArchiveStatusUpdaterBolt extends CapabilityStatusUpdaterBolt {

    private Path root;

    private String job;

    /**
     * <b>Настраивает архив перед capability-aware updater.</b>
     *
     * <p>Сохраняет существующий порядок проверки root/job и superclass.
     *
     * @param conf конфигурация компонента
     * @param context контекст Storm
     * @param collector collector компонента
     */
    @Override
    public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector) {
        root = ArchiveParseFilter.requiredRoot(conf);
        job = ArchiveParseFilter.requiredJob(conf);
        super.prepare(conf, context, collector);
    }

    /**
     * <b>Сохраняет наблюдение перед защищённым PutURLs.</b>
     *
     * <p>Ошибка каталога распространяется до сетевой записи.
     *
     * @param url URL страницы
     * @param status статус либо карта статуса
     * @param metadata метаданные crawler
     * @param nextFetch рассчитанная superclass дата следующей попытки
     * @param tuple исходный tuple
     */
    @Override
    public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple) {
        try {
            ArchiveStatusUpdaterBolt.recordOutcome(root, job, url, status, metadata, nextFetch);
        } catch (IOException e) {
            throw new UncheckedIOException("Archive outcome commit failed", e);
        }
        super.store(url, status, metadata, nextFetch, tuple);
    }
}
