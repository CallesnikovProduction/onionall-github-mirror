package dev.onionall.brawler.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArchiveStoreTest {
    private static final String HOST="aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
    private static final String URL="http://"+HOST+"/";
    private static byte[] html(String text){return ("<html><title>Research</title><body>"+text+"</body></html>").getBytes(StandardCharsets.UTF_8);}
    private static String plan(ArchiveStore store,int depth,int count) throws Exception {
        String id=(String)store.createPlan(List.of(URL),depth,count,"user").get("plan_id");store.approvePlan(id);return id;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object> map,String key){return (List<Map<String,Object>>)map.get(key);}

    @Test public void immutableSameOriginPlanCanonicalizesSeedsAndRequiresApproval() throws Exception {
        try(ArchiveStore store=new ArchiveStore(Files.createTempDirectory("archive-plan"))){
            Map<String,Object> draft=store.createPlan(List.of(URL,URL+"#duplicate","http://"+HOST+":80/"),1,2,"manual-provider");
            assertEquals(List.of(URL),draft.get("seeds"));assertEquals(false,draft.get("approved"));
            String id=(String)draft.get("plan_id");
            assertThrows(IOException.class,()->store.archive(id,URL,0,URL,200,"text/html",html("safe"),List.of()));
            assertEquals(true,store.approvePlan(id).get("approved"));
            assertEquals("BLOCKED_ADR_0002",store.plan(id).get("admission"));
            assertThrows(IOException.class,()->store.createPlan(List.of(URL,"https://"+HOST+"/"),1,2,"user"));
            assertThrows(IOException.class,()->store.createPlan(List.of(URL),6,1,"user"));
        }
    }
    @Test public void snapshotsDeduplicateHashesAndSurviveReopenWithHistoryAndLinks() throws Exception {
        Path root=Files.createTempDirectory("archive-reopen");String job,page;
        byte[] body=html("unique keyword");
        try(ArchiveStore store=new ArchiveStore(root)){
            job=plan(store,2,3);
            var first=store.archive(job,URL,0,URL,200,"text/html; charset=utf-8",body,List.of(Map.of("target_url",URL+"b","disposition","ADMITTED")));
            page=(String)first.get("page_id");
            assertEquals(page,store.archive(job,URL,0,URL,200,"text/html",body,List.of()).get("page_id"));
            assertEquals(1L,store.job(job).get("snapshots"));
            store.archive(job,URL+"b",1,URL,200,"text/html",body,List.of());
            try(var files=Files.list(root.resolve("objects"))){assertEquals(1L,files.count());}
            store.archive(job,URL,0,URL,200,"text/html",html("new version"),List.of());
            assertEquals(3L,store.job(job).get("snapshots"));
            assertEquals(first.get("content_hash"),store.archive(job,URL,0,URL,200,"text/html",body,List.of()).get("content_hash"));
        }
        try(ArchiveStore store=new ArchiveStore(root)){
            assertEquals(2,list(store.pages(job,10),"pages").size());
            assertEquals(2,list(store.page(page),"history").size());
            assertEquals(2,list(store.search("unique keyword",job,10),"results").size());
            assertEquals(0,list(store.search("%' OR 1=1 --",job,10),"results").size());
            assertTrue(Files.isRegularFile(Path.of((String)store.page(page).get("storage_location"))));
        }
    }
    @Test public void depthScopePageAndBodyLimitsRejectWithoutExtraRecords() throws Exception {
        try(ArchiveStore store=new ArchiveStore(Files.createTempDirectory("archive-limits"))){
            String job=plan(store,0,1);
            assertThrows(IOException.class,()->store.archive(job,URL+"deep",1,URL,200,"text/html",html("x"),List.of()));
            assertThrows(IOException.class,()->store.archive(job,"http://example.com/",0,URL,200,"text/html",html("x"),List.of()));
            assertThrows(IOException.class,()->store.archive(job,URL,0,URL,200,"application/octet-stream",html("x"),List.of()));
            assertThrows(IOException.class,()->store.archive(job,URL,0,URL,200,"text/html",new byte[131073],List.of()));
            store.archive(job,URL,0,URL,200,"text/html",html("first"),List.of());
            assertThrows(IOException.class,()->store.archive(job,URL+"second",0,URL,200,"text/html",html("x"),List.of()));
            assertEquals(1L,store.job(job).get("archived_pages"));
        }
    }
    @Test public void manualImportNeverClaimsFetchOrRobotsAndLaterFetchHasSeparateSnapshot() throws Exception {
        try(ArchiveStore store=new ArchiveStore(Files.createTempDirectory("archive-manual"))){
            String job=plan(store,1,2);byte[] body=html("manual");
            var imported=store.importHtml(job,URL,body,List.of());
            assertEquals(0,imported.get("http_status"));assertNull(imported.get("fetched_at"));assertEquals("MANUAL_IMPORT",imported.get("source_kind"));
            var fetched=store.archive(job,URL,0,URL,200,"text/html",body,List.of());
            assertEquals("CRAWLER",fetched.get("source_kind"));assertEquals(2,list(fetched,"history").size());
        }
    }
    @Test public void cancellationRollsBackPageAndDoesNotExposePartialObject() throws Exception {
        Path root=Files.createTempDirectory("archive-cancel");
        try(ArchiveStore store=new ArchiveStore(root)){
            String job=plan(store,1,2);Thread.currentThread().interrupt();
            try{assertThrows(IOException.class,()->store.archive(job,URL,0,URL,200,"text/html",html("x"),List.of()));}
            finally{Thread.interrupted();}
            assertEquals(0L,store.job(job).get("archived_pages"));
            try(var files=Files.list(root.resolve("objects"))){assertEquals(0L,files.count());}
        }
    }
    @Test public void databaseFailureAfterBlobPublishNeverCommitsIncompleteSnapshot() throws Exception {
        Path root=Files.createTempDirectory("archive-rollback");
        try(ArchiveStore store=new ArchiveStore(root)){
            String job=plan(store,1,2);
            var field=ArchiveStore.class.getDeclaredField("db");field.setAccessible(true);Connection db=(Connection)field.get(store);
            try(var statement=db.createStatement()){statement.execute("ALTER TABLE snapshots ADD CONSTRAINT injected_failure CHECK(http_status<>200)");}
            assertThrows(IOException.class,()->store.archive(job,URL,0,URL,200,"text/html",html("x"),List.of()));
            assertEquals(0L,store.job(job).get("snapshots"));assertEquals(0L,store.job(job).get("archived_pages"));
            try(var statement=db.createStatement()){statement.execute("ALTER TABLE snapshots DROP CONSTRAINT injected_failure");}
            store.archive(job,URL,0,URL,200,"text/html",html("x"),List.of());
            assertEquals(1L,store.job(job).get("snapshots"));
        }
    }
    @Test public void symlinkRootAndBlobAreRejectedWithoutWritingOutsideArchive() throws Exception {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path parent=Files.createTempDirectory("archive-symlink"),outside=Files.createDirectory(parent.resolve("outside"));
        Path linked=parent.resolve("linked");Files.createSymbolicLink(linked,outside);
        assertThrows(IOException.class,()->new ArchiveStore(linked));
        Path root=parent.resolve("real");
        try(ArchiveStore store=new ArchiveStore(root)){
            String job=plan(store,1,2);byte[] body=html("x");String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Path target=outside.resolve("secret");Files.writeString(target,"unchanged");Files.createSymbolicLink(root.resolve("objects").resolve(digest+".html"),target);
            assertThrows(IOException.class,()->store.archive(job,URL,0,URL,200,"text/html",body,List.of()));
            assertEquals("unchanged",Files.readString(target));
        }
    }
    @Test public void failureCategoryIsBoundedStructuredAndPersists() throws Exception {
        Path root=Files.createTempDirectory("archive-failure");String job;
        try(ArchiveStore store=new ArchiveStore(root)){
            job=plan(store,1,2);var first=store.recordFailure(job,URL,"FETCH_ERROR");assertEquals(first.get("failure_id"),store.recordFailure(job,URL,"FETCH_ERROR").get("failure_id"));
            assertThrows(IOException.class,()->store.recordFailure(job,URL,"raw secret URL: error"));
        }
        try(ArchiveStore store=new ArchiveStore(root)){
            assertEquals(1L,store.job(job).get("failures"));assertEquals("FETCH_ERROR",list(store.job(job),"failure_records").getFirst().get("category"));
        }
    }
}
