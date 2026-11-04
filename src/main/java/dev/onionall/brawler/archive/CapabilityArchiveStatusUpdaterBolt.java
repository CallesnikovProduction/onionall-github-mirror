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

/** Durable-candidate client; the synthetic stock-Frontier archive fixture stays separate. */
public final class CapabilityArchiveStatusUpdaterBolt extends CapabilityStatusUpdaterBolt {
    private Path root;
    private String job;
    @Override public void prepare(Map<String,Object> conf, TopologyContext context, OutputCollector collector) {
        root = ArchiveParseFilter.requiredRoot(conf);
        job = ArchiveParseFilter.requiredJob(conf);
        super.prepare(conf, context, collector);
    }
    @Override public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple) {
        try { ArchiveStatusUpdaterBolt.recordOutcome(root, job, url, status, metadata, nextFetch); }
        catch (IOException e) { throw new UncheckedIOException("Archive outcome commit failed", e); }
        super.store(url, status, metadata, nextFetch, tuple);
    }
}
