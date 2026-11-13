package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Настоящий CLI/H2, без сетевых успехов или fake archive; проверяет новый immutable thread scope.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ResearchThreadCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private record Result(int code, JsonNode value) { }
    private Result command(Path archive,String...args) throws Exception {
        var argv=new ArrayList<>(List.of("--json","--archive-dir",archive.toString()));
        argv.addAll(List.of(args));
        var out=new StringWriter();var err=new StringWriter();
        int code=Onionall.execute(argv.toArray(String[]::new),new PrintWriter(out,true),new PrintWriter(err,true),false,Map.of(),
            (config,url)->{throw new AssertionError("Plan/readback must not fetch");});
        assertEquals("",err.toString());
        return new Result(code,new ObjectMapper().readTree(out.toString()));
    }
    @Test public void threadPlanSeparatesFivePagePaginationBudgetFromNavigationAndRequiresApproval() throws Exception {
        Path archive=temporary.newFolder().toPath().resolve("archive");
        String url="http://"+OnionSmoke.HOST+"/thread?page=1&id=123";
        var result=command(archive,"research","thread","plan",url,"--max-pages","5");
        assertEquals(0,result.code);var doc=result.value;
        assertEquals("THREAD",doc.path("mode").asText());
        assertEquals(0,doc.path("navigation_depth").asInt(-1));
        assertEquals(4,doc.path("depth").asInt());
        assertEquals(5,doc.path("global_page_budget").asInt());
        assertFalse(doc.path("approved").asBoolean());
        String id=doc.path("session_id").asText(),job=doc.path("seeds").get(0).path("job_id").asText();
        var child=command(archive,"crawl","show",job).value;
        assertEquals("m61:thread",child.path("provider").asText());
        assertEquals(5,child.path("max_pages").asInt());
        assertEquals("http://"+OnionSmoke.HOST+"/thread?id=123&page=1",child.path("seeds").get(0).asText());
        assertNotEquals(0,command(archive,"research","thread","crawl",id,"--runtime","absent","--admission-token-file","absent").code);
        assertFalse(command(archive,"research","candidates",id).value.path("approved").asBoolean());
        assertEquals(0,command(archive,"research","approve",id).code);
        assertTrue(command(archive,"research","candidates",id).value.path("approved").asBoolean());
        assertNotEquals(0,command(archive,"research","plan","--session",id,"--depth","3","--pages-per-seed","5","--global-pages","5").code);
        assertEquals(0,command(archive,"research","thread","show",id).value.path("threads").size());
    }
    @Test public void invalidThreadBudgetCannotProduceAnApprovedScope() throws Exception {
        Path archive=temporary.newFolder().toPath().resolve("archive");
        String url="http://"+OnionSmoke.HOST+"/thread";
        for(String cap:List.of("0","6")) assertNotEquals(0,command(archive,"research","thread","plan",url,"--max-pages",cap).code);
    }
    @Test public void explicitSiteSessionCanUseDepthThreeWithSeparateImmutableCap() throws Exception {
        Path archive=temporary.newFolder().toPath().resolve("archive");
        String id=command(archive,"research","import","--seed","http://"+OnionSmoke.HOST+"/").value.path("session_id").asText();
        command(archive,"research","select",id,"--all-valid");
        var planned=command(archive,"research","plan","--session",id,"--depth","3","--pages-per-seed","20","--global-pages","20");
        assertEquals(0,planned.code);assertEquals(3,planned.value.path("depth").asInt());
        assertFalse(planned.value.path("approved").asBoolean());
    }
}
