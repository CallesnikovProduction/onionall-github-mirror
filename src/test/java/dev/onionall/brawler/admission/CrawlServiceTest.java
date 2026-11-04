package dev.onionall.brawler.admission;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CrawlServiceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String URL="http://"+dev.onionall.brawler.tools.OnionSmoke.HOST+"/";
    private static final CrawlService.Execution OFFLINE=new CrawlService.Execution(){
        public Map<String,Object> status(Path root,String id,Path runtime,Path token){throw new AssertionError("Unexpected execution access");}
        public Map<String,Object> start(Path root,String id,Path runtime,Path token){throw new AssertionError("Unexpected admission/worker access");}
    };
    @Test public void catalogOperationsAndLiveKeysetDoNotContactExecutionOrInferAdmission()throws Exception {
        var service=new CrawlService(temporary.newFolder().toPath(),OFFLINE);
        Set<String> original=new HashSet<>();
        for(int i=0;i<3;i++)original.add((String)service.createPlan(List.of(URL),1,3,"service fixture").get("plan_id"));
        var first=service.plans(null,2);assertEquals(true,first.get("truncated"));assertEquals(false,first.get("membership_snapshot"));
        List<?> rows=(List<?>)first.get("plans");assertEquals(2,rows.size());Set<String> read=new HashSet<>();
        for(var row:rows){var value=(Map<?,?>)row;read.add((String)value.get("plan_id"));assertEquals("UNOBSERVED",value.get("admission"));}
        service.createPlan(List.of(URL),1,3,"concurrent append");
        String cursor=(String)first.get("next_cursor");
        while(cursor!=null) {
            var page=service.plans(cursor,2);
            for(var row:(List<?>)page.get("plans"))assertTrue("No repeated old rows",read.add((String)((Map<?,?>)row).get("plan_id")));
            cursor=(String)page.get("next_cursor");
        }
        assertTrue("No preexisting plan omitted",read.containsAll(original));
        String id=original.iterator().next();assertEquals(false,service.plan(id).get("approved"));
        assertEquals(true,service.approve(id).get("approved"));assertEquals("UNOBSERVED",service.plan(id).get("admission"));
        assertEquals(0L,service.errors(id,null,20).get("observation_count"));
        assertThrows(IOException.class,()->service.plans(null,101));assertThrows(IOException.class,()->service.plans("invalid cursor",2));
    }
    @Test public void missingRuntimePreservesBlockedContractWithoutAnonymousExecution()throws Exception {
        var service=new CrawlService(temporary.newFolder().toPath(),OFFLINE);
        String id=(String)service.createPlan(List.of(URL),1,3,"unconfigured").get("plan_id");
        for(int i=0;i<2;i++) {
            var blocked=service.start(id,null,null);assertEquals(7,blocked.get("exit_code"));
            assertEquals(false,blocked.get("passed"));assertEquals("BLOCKED",blocked.get("admission"));assertEquals(0,blocked.get("network_requests"));
            service.approve(id);
        }
    }
    @Test public void executionInputsAndVersionedResponsesAreForwardedWithoutReinterpretation()throws Exception {
        Path root=temporary.newFolder().toPath(),runtime=root.resolve("runtime.json"),token=root.resolve("private capability");String id=UUID.randomUUID().toString();
        var response=Map.<String,Object>of("job_status_version",1,"job_state","UNKNOWN");AtomicInteger calls=new AtomicInteger();
        CrawlService.Execution wire=new CrawlService.Execution(){
            private Map<String,Object> forward(Path a,String b,Path c,Path d){assertSame(root,a);assertEquals(id,b);assertSame(runtime,c);assertSame(token,d);calls.incrementAndGet();return response;}
            public Map<String,Object> status(Path a,String b,Path c,Path d){return forward(a,b,c,d);}
            public Map<String,Object> start(Path a,String b,Path c,Path d){return forward(a,b,c,d);}
        };
        var service=new CrawlService(root,wire);assertSame(response,service.status(id,runtime,token));assertSame(response,service.start(id,runtime,token));assertEquals(2,calls.get());
    }
    @Test public void authorityFailurePropagatesOnceWithoutFallbackOrRetry()throws Exception {
        Path root=temporary.newFolder().toPath();IOException failure=new IOException("Controlled authority failure");AtomicInteger calls=new AtomicInteger();
        CrawlService.Execution wire=new CrawlService.Execution(){
            public Map<String,Object> status(Path a,String b,Path c,Path d)throws IOException{calls.incrementAndGet();throw failure;}
            public Map<String,Object> start(Path a,String b,Path c,Path d)throws IOException{calls.incrementAndGet();throw failure;}
        };
        var service=new CrawlService(root,wire);
        assertSame(failure,assertThrows(IOException.class,()->service.start("fixture",root.resolve("runtime"),root.resolve("token"))));
        assertSame(failure,assertThrows(IOException.class,()->service.status("fixture",root.resolve("runtime"),root.resolve("token"))));assertEquals(2,calls.get());
    }
}
