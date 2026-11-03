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
import org.apache.stormcrawler.urlfrontier.StatusUpdaterBolt;

/** Observe the installed scheduler's final status before normal Frontier storage.
 * No policy is duplicated: maxFetchErrors and nextFetch belong to the superclass.
 * Catalog failure propagates into its existing tuple-fail path, before ACK.
 */
public final class ArchiveStatusUpdaterBolt extends StatusUpdaterBolt {
    private Path root;
    private String job;
    @Override public void prepare(Map<String,Object> conf, TopologyContext context, OutputCollector collector) {
        root = ArchiveParseFilter.requiredRoot(conf);
        job = ArchiveParseFilter.requiredJob(conf);
        super.prepare(conf, context, collector);
    }
    static void recordOutcome(Path root, String job, String url, Status status, Metadata metadata,
                              Optional<Date> nextFetch) throws IOException {
        if (status != Status.FETCH_ERROR && status != Status.ERROR && status != Status.REDIRECTION) return;
        String cause = metadata.getFirstValue("error.cause");
        String category = status == Status.REDIRECTION ? "REDIRECTION" :
            "robots.txt".equals(cause) ? "ROBOTS_DENIED" :
            status == Status.ERROR && "maxFetchErrors".equals(cause) ? "TERMINAL_MAX_FETCH_ERRORS" :
            status == Status.ERROR && nextFetch.isEmpty() ? "TERMINAL_ERROR" : status.name();
        try (var store = new ArchiveStore(root)) { store.recordFailure(job, url, category); }
    }
    @Override public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple) {
        try { recordOutcome(root, job, url, status, metadata, nextFetch); }
        catch (IOException e) { throw new UncheckedIOException("Archive outcome commit failed", e); }
        super.store(url, status, metadata, nextFetch, tuple);
    }
}
