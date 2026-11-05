package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ArchiveCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String URL="http://"+OnionSmoke.HOST+"/";
    private record Result(int code,JsonNode json,String output) { }
    private Result run(Path archive,String... command)throws Exception {
        var args=new ArrayList<>(List.of("--archive-dir",archive.toString(),"--json"));args.addAll(List.of(command));
        StringWriter output=new StringWriter(), errors=new StringWriter();
        int code=Onionall.execute(args.toArray(String[]::new),new PrintWriter(output,true),new PrintWriter(errors,true),false,Map.of("NO_COLOR","1"),
            (config,url)->{throw new AssertionError("Archive/plan operations must stay offline");});
        assertFalse(output.toString().contains("\033"));assertEquals("",errors.toString());
        return new Result(code,new ObjectMapper().readTree(output.toString()),output.toString());
    }
    private String plan(Path archive)throws Exception {
        Result result=run(archive,"crawl","plan","--seed",URL,"--depth","2","--max-pages","5");
        assertEquals(result.output,0,result.code);assertEquals("UNOBSERVED",result.json.path("admission").asText());
        return result.json.path("plan_id").asText();
    }
    @Test public void approvedPlanAndOfflineArchiveSurviveCliRecreation()throws Exception {
        Path directory=temporary.newFolder().toPath();Path archive=directory.resolve("catalog");
        String id=plan(archive);assertFalse(id.isBlank());
        assertEquals(0,run(archive,"crawl","show",id).code);
        Path html=directory.resolve("manual.html");Files.writeString(html,"<html><title>Research needle</title><body>Local searchable archive<a href='next#fragment'>next</a><script>throw new Error()</script></body></html>");
        assertNotEquals(0,run(archive,"archive","import",html.toString(),"--plan",id,"--url",URL).code);
        assertEquals(0,run(archive,"crawl","approve",id).code);
        Result imported=run(archive,"archive","import",html.toString(),"--plan",id,"--url",URL);
        assertEquals(imported.output,0,imported.code);
        assertFalse(imported.json.path("transport_verified").asBoolean(true));assertFalse(imported.json.path("robots_verified").asBoolean(true));
        assertEquals("MANUAL_IMPORT",imported.json.path("source_kind").asText());
        assertEquals(0,imported.json.path("http_status").asInt(-1));
        assertTrue(imported.json.path("fetched_at").isNull());
        String page=imported.json.path("page_id").asText();assertFalse(page.isBlank());
        Result listed=run(archive,"pages","--job",id);assertEquals(0,listed.code);assertEquals(1,listed.json.path("pages").size());
        Result shown=run(archive,"page","show",page);assertEquals(0,shown.code);assertTrue(shown.output.contains("Research needle"));
        Result searched=run(archive,"search-local","needle","--job",id);assertEquals(0,searched.code);assertEquals(1,searched.json.path("results").size());
        assertEquals(0,run(archive,"crawl","status",id).code);
        Result started=run(archive,"crawl","start","--plan",id);assertEquals(7,started.code);assertEquals("BLOCKED",started.json.path("admission").asText());
        assertEquals(0,started.json.path("network_requests").asInt(-1));
    }
    @Test public void seedImportScopeAndBudgetValidationDoNotAdmitWork()throws Exception {
        Path directory=temporary.newFolder().toPath();Path archive=directory.resolve("catalog");
        Path seeds=directory.resolve("urls.txt");Files.writeString(seeds,"# manually selected\n"+URL+"\n"+URL+"\n");
        Result result=run(archive,"crawl","plan","--import",seeds.toString(),"--provider","manual-selection","--depth","1","--max-pages","3");
        assertEquals(result.output,0,result.code);
        assertEquals("UNOBSERVED",result.json.path("admission").asText());
        assertNotEquals(0,run(archive,"crawl","plan","--seed",URL,"--scope","all-hosts").code);
        assertNotEquals(0,run(archive,"crawl","plan","--seed",URL,"--depth","-1").code);
        assertNotEquals(0,run(archive,"crawl","plan","--seed","https://example.com/").code);
        assertNotEquals(0,run(archive,"crawl","plan","--seed",URL,"--max-pages","0").code);
    }
    @Test public void runtimeStartRequiresApprovalBeforeAnyRpc()throws Exception {
        Path directory=temporary.newFolder().toPath();Path archive=directory.resolve("catalog");String id=plan(archive);
        Path runtime=directory.resolve("runtime.json");Files.writeString(runtime,"{}");
        Result result=run(archive,"crawl","start","--plan",id,"--runtime",runtime.toString());
        assertNotEquals(0,result.code);assertTrue(result.output.contains("approval"));
        assertEquals("DRAFT",run(archive,"crawl","status",id).json.path("state").asText());
    }
    @Test public void failureHistoryIsBoundedOfflineAndDoesNotClaimDistinctFetchAttempts()throws Exception {
        Path archive=temporary.newFolder().toPath().resolve("catalog");String id=plan(archive);
        run(archive,"crawl","approve",id);
        try(var store=new dev.onionall.brawler.archive.ArchiveStore(archive)) {
            for(int i=0;i<3;i++)store.recordFailure(id,URL,"FETCH_ERROR");
        }
        Result first=run(archive,"crawl","errors",id,"--limit","1");
        assertEquals(first.output,0,first.code);assertEquals(1,first.json.path("history_version").asInt());
        assertEquals(1,first.json.path("rows").size());assertEquals(3,first.json.path("observation_count").asInt());
        assertTrue(first.json.path("fetch_attempt_count").isNull());assertFalse(first.json.path("legacy_aggregates_are_attempts").asBoolean());
        assertEquals(1,first.json.path("legacy_aggregate_count").asInt());assertTrue(first.json.path("truncated").asBoolean());
        Result next=run(archive,"crawl","errors",id,"--limit","1","--cursor",first.json.path("next_cursor").asText());
        assertEquals(0,next.code);assertEquals(1,next.json.path("rows").size());
        assertNotEquals(first.json.path("rows").get(0).path("observation_id"),next.json.path("rows").get(0).path("observation_id"));
        Result status=run(archive,"crawl","status",id);
        assertEquals(0,status.code);assertEquals(3,status.json.path("failure_observation_count").asInt());
        assertTrue(status.json.path("fetch_attempt_count").isNull());assertTrue(status.json.path("failure_history_hint").asText().contains(id));
    }
    @Test public void unavailableConfiguredReadbackReturnsUnknownWithNonzeroExitAndNoAdmission()throws Exception {
        Path root=temporary.newFolder().toPath(),archive=root.resolve("catalog");String id=plan(archive);
        run(archive,"crawl","approve",id);
        var server=io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1",0)).build().start();
        try {
            Path runtime=root.resolve("runtime.json"),token=root.resolve("token");
            Files.writeString(runtime,new ObjectMapper().writeValueAsString(Map.of("frontier_host","127.0.0.1","frontier_port",server.getPort())));
            Files.writeString(token,"synthetic-unavailable-status-token-00000");
            Result result=run(archive,"crawl","status",id,"--runtime",runtime.toString(),"--admission-token-file",token.toString());
            assertNotEquals(0,result.code);assertEquals("UNKNOWN",result.json.path("job_state").asText());
            assertFalse(result.json.path("authoritative_readback_current").asBoolean());
            assertTrue(result.json.path("counts").path("pending").isNull());
            assertFalse(result.json.has("authoritative_admission"));
        }finally{server.shutdownNow();server.awaitTermination();}
    }
    @Test public void invalidRuntimeCannotAdmitAnApprovedPlan()throws Exception {
        Path directory=temporary.newFolder().toPath();Path archive=directory.resolve("catalog");String id=plan(archive);
        run(archive,"crawl","approve",id);
        Path runtime=directory.resolve("runtime.json");Files.writeString(runtime,"{}");
        Result result=run(archive,"crawl","start","--plan",id,"--runtime",runtime.toString());
        assertNotEquals(0,result.code);assertEquals("APPROVED",run(archive,"crawl","status",id).json.path("state").asText());
    }
    @Test public void providerSearchOnlyReturnsAssistedOfficialUrl()throws Exception {
        Path archive=temporary.newFolder().toPath().resolve("catalog");
        Result result=run(archive,"search-provider","research & onions","--provider","ahmia");
        assertEquals(result.output,0,result.code);
        assertTrue(result.output.contains("https://ahmia.fi/search/?q="));
        assertTrue(result.output.contains("USER_ACTION_REQUIRED"));
        assertNotEquals(0,run(archive,"search-provider","research","--provider","torch").code);
        assertNotEquals(0,run(archive,"search-provider","x".repeat(257)).code);
        assertFalse(Files.exists(archive));
    }
    @Test public void boundedExportRefusesOverwriteAndImportsRejectOversize()throws Exception {
        Path directory=temporary.newFolder().toPath();Path archive=directory.resolve("catalog");String id=plan(archive);
        assertEquals(0,run(archive,"crawl","approve",id).code);
        Path destination=directory.resolve("summary.json");
        assertEquals(0,run(archive,"archive","export","--job",id,"--output",destination.toString()).code);
        String initial=Files.readString(destination);
        assertEquals(6,run(archive,"archive","export","--job",id,"--output",destination.toString()).code);
        assertEquals(initial,Files.readString(destination));
        Path html=directory.resolve("oversized.html");Files.write(html,new byte[131073]);
        assertEquals(6,run(archive,"archive","import",html.toString(),"--plan",id,"--url",URL).code);
        assertNotEquals(0,run(archive,"search-local","needle","--limit","10001").code);
    }
}
