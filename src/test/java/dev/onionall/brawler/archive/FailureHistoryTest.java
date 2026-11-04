package dev.onionall.brawler.archive;

import java.io.IOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.persistence.Status;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FailureHistoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String URL="http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";
    private static String plan(ArchiveStore store)throws Exception {
        String id=(String)store.createPlan(List.of(URL),1,3,"test").get("plan_id");store.approvePlan(id);return id;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> value){return (List<Map<String,Object>>)value.get("rows");}
    private static FailureObservation transient503() {
        return new FailureObservation(FailureObservation.Phase.FETCH,FailureObservation.Source.STORM_STATUS_INTENT,503,
            true,false,Instant.now().plusSeconds(7200).getEpochSecond(),FailureObservation.Transport.HTTP_ERROR);
    }
    @Test public void repeatedObservationsRemainDistinctAndPagingDoesNotLoseOldRowsDuringAppend() throws Exception {
        Path root=temporary.newFolder().toPath();String id;
        try(var store=new ArchiveStore(root)) {
            id=plan(store);Object identity=null;
            for(int i=0;i<3;i++) {
                var result=store.recordFailure(id,URL,"FETCH_ERROR",transient503());
                if(identity==null)identity=result.get("failure_id");else assertEquals(identity,result.get("failure_id"));
            }
            assertEquals(1L,store.job(id).get("failures"));assertEquals(3L,store.job(id).get("failure_observation_count"));
            Map<String,Object> first=store.failureHistory(id,null,2);
            assertEquals(2,rows(first).size());assertEquals(3L,first.get("observation_count"));
            assertNull(first.get("fetch_attempt_count"));assertEquals(false,first.get("legacy_aggregates_are_attempts"));
            try(var other=new ArchiveStore(root)) {
                var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
                try {writer.submit(()->{
                    try {other.recordFailure(id,URL,"FETCH_ERROR",transient503());}catch(IOException e){throw new java.io.UncheckedIOException(e);}
                }).get(10,java.util.concurrent.TimeUnit.SECONDS);}
                finally{writer.shutdownNow();}
            }
            Map<String,Object> last=store.failureHistory(id,(String)first.get("next_cursor"),2);
            assertEquals(1,rows(last).size());assertNull(last.get("next_cursor"));
            Set<Object> seen=new HashSet<>();rows(first).forEach(row->assertTrue(seen.add(row.get("observation_id"))));rows(last).forEach(row->assertTrue(seen.add(row.get("observation_id"))));assertEquals(3,seen.size());
            assertEquals(4L,store.failureHistory(id,null,100).get("observation_count"));
            assertThrows(IOException.class,()->store.failureHistory(id,"!",20));
            String other=plan(store);
            assertThrows(IOException.class,()->store.failureHistory(other,(String)first.get("next_cursor"),20));
            assertThrows(IOException.class,()->store.failureHistory(id,null,101));
        }
        try(var store=new ArchiveStore(root)) {
            Map<String,Object> latest=rows(store.failureHistory(id,null,100)).getLast();
            assertEquals(503,latest.get("http_status"));assertEquals(true,latest.get("retryable"));assertEquals(false,latest.get("terminal"));
            assertEquals("STORM_STATUS_INTENT",latest.get("source"));assertNull(latest.get("applied_to_frontier"));
            assertEquals(latest,store.job(id).get("latest_failure_observation"));
        }
    }
    @Test public void observationFailureRollsBackAggregateAndLeavesArchiveIntact() throws Exception {
        Path root=temporary.newFolder().toPath();String id;
        try(var store=new ArchiveStore(root)) {
            id=plan(store);store.archive(id,URL,0,URL,200,"text/html","<html>retained</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),List.of());
            store.recordFailure(id,URL,"FETCH_ERROR",transient503());
        }
        // Fixture-only constraint injects a failing event insert, without deleting data.
        try(var db=DriverManager.getConnection("jdbc:h2:file:"+root.resolve("catalog")+";AUTO_SERVER=FALSE","sa","");var sql=db.createStatement()) {
            sql.execute("ALTER TABLE failure_observations_v1 ADD CONSTRAINT fixture_reject_terminal CHECK (category <> 'TERMINAL_ERROR')");
        }
        try(var store=new ArchiveStore(root)) {
            assertThrows(IOException.class,()->store.recordFailure(id,URL,"TERMINAL_ERROR"));
            assertEquals(1L,store.job(id).get("failures"));assertEquals(1L,store.job(id).get("failure_observation_count"));
            assertEquals(1L,store.job(id).get("snapshots"));
        }
    }
    @Test public void staleHttpMetadataNeverTurnsTransportFailureIntoCurrentHttpResponse() throws Exception {
        Path root=temporary.newFolder().toPath();String id;
        try(var store=new ArchiveStore(root)){id=plan(store);}
        Metadata md=new Metadata();md.setValue("fetch.statusCode","503");md.setValue("protocol.tor.category","HTTP_ERROR");
        md.setValue("fetch.exception","java.io.IOException: synthetic-private-value");
        ArchiveStatusUpdaterBolt.recordOutcome(root,id,URL,Status.FETCH_ERROR,md,Optional.of(Date.from(Instant.now().plusSeconds(7200))));
        md.setValue("fetch.exception","Socket timeout fetching");
        ArchiveStatusUpdaterBolt.recordOutcome(root,id,URL,Status.FETCH_ERROR,md,Optional.of(Date.from(Instant.now().plusSeconds(7200))));
        md.remove("fetch.exception");md.setValue("error.cause","robots.txt");
        ArchiveStatusUpdaterBolt.recordOutcome(root,id,URL,Status.ERROR,md,Optional.empty());
        try(var store=new ArchiveStore(root)) {
            List<Map<String,Object>> events=rows(store.failureHistory(id,null,100));
            assertEquals(3,events.size());assertNull(events.getFirst().get("http_status"));
            assertEquals("UNKNOWN",events.getFirst().get("transport_category"));
            assertEquals("REQUEST_TIMEOUT",events.get(1).get("transport_category"));assertNull(events.get(1).get("http_status"));
            assertEquals("ROBOTS",events.getLast().get("phase"));assertNull(events.getLast().get("http_status"));
            assertTrue(events.stream().noneMatch(row->row.toString().contains("synthetic-private-value")));
        }
    }
    @Test public void preExtensionAggregatesRemainAggregatesAndNeverBecomeFabricatedAttempts() throws Exception {
        Path root=temporary.newFolder().toPath();String id;
        try(var store=new ArchiveStore(root)){id=plan(store);}
        // Models an old binary writing its existing table, not an observation backfill.
        try(var db=DriverManager.getConnection("jdbc:h2:file:"+root.resolve("catalog")+";AUTO_SERVER=FALSE","sa","");var insert=db.prepareStatement("INSERT INTO failures VALUES(?,?,?,?,?)")) {
            insert.setString(1,UUID.randomUUID().toString());insert.setString(2,id);insert.setString(3,URL);insert.setString(4,"FETCH_ERROR");insert.setString(5,Instant.EPOCH.toString());insert.executeUpdate();
        }
        try(var store=new ArchiveStore(root)) {
            var history=store.failureHistory(id,null,20);
            assertEquals(1L,history.get("legacy_aggregate_count"));assertEquals(0L,history.get("observation_count"));
            assertTrue(rows(history).isEmpty());assertNull(history.get("fetch_attempt_count"));
        }
    }
}
