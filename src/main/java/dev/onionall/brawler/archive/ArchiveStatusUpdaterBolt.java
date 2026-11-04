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
        Integer http=null;
        String code=metadata.getFirstValue("fetch.statusCode");
        String exception=metadata.getFirstValue("fetch.exception");
        boolean robots=category.equals("ROBOTS_DENIED");
        // Installed SimpleFetcherBolt clears fetch.exception, but can retain the
        // previous HTTP/protocol fields when a new request fails before response.
        if(exception==null && !robots && code!=null && code.matches("[1-5][0-9]{2}"))http=Integer.valueOf(code);
        FailureObservation.Transport transport=FailureObservation.Transport.UNKNOWN;
        String candidate=robots?null:exception!=null?exception:metadata.getFirstValue("protocol.tor.category");
        if(candidate!=null)try {transport=FailureObservation.Transport.valueOf(candidate);}
        catch(IllegalArgumentException ignored) { /* Never persist raw exception text. */ }
        if("Socket timeout fetching".equals(exception))transport=FailureObservation.Transport.REQUEST_TIMEOUT;
        String source=metadata.getFirstValue("error.source");
        FailureObservation.Phase phase=category.equals("ROBOTS_DENIED")?FailureObservation.Phase.ROBOTS:
            status==Status.FETCH_ERROR || category.equals("TERMINAL_MAX_FETCH_ERRORS") || status==Status.REDIRECTION?FailureObservation.Phase.FETCH:
            "parser".equals(source)||"parsing".equals(source)?FailureObservation.Phase.PARSE:
            "archive".equals(source)?FailureObservation.Phase.ARCHIVE:FailureObservation.Phase.UNKNOWN;
        boolean scheduledFailure=status==Status.FETCH_ERROR || status==Status.ERROR;
        var observation=new FailureObservation(phase,FailureObservation.Source.STORM_STATUS_INTENT,http,
            scheduledFailure?Boolean.valueOf(nextFetch.isPresent()):null,
            scheduledFailure?Boolean.valueOf(nextFetch.isEmpty()):null,
            nextFetch.map(date->date.getTime()/1000).orElse(null),transport);
        try (var store = new ArchiveStore(root)) { store.recordFailure(job, url, category, observation); }
    }
    @Override public void store(String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple) {
        try { recordOutcome(root, job, url, status, metadata, nextFetch); }
        catch (IOException e) { throw new UncheckedIOException("Archive outcome commit failed", e); }
        super.store(url, status, metadata, nextFetch, tuple);
    }
}
