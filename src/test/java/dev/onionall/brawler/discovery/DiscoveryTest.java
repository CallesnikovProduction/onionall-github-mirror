package dev.onionall.brawler.discovery;
import dev.onionall.brawler.tor.TorPolicyTest;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryTest {
    private static String host(int n)throws Exception {
        // Independent test packages reuse only the existing test vector builder.
        var m=TorPolicyTest.class.getDeclaredMethod("onion",int.class);m.setAccessible(true);return (String)m.invoke(null,n);
    }
    private static Config config(String... hosts) {
        Config c=new Config();c.put("discovery.budget.mode","local-test");c.put("discovery.crawl.session.id","unit");c.put("tor.mode","ONLY");c.put("tor.socks.host","tor");c.put("tor.socks.port",9050);c.put("tor.allowed.hosts",List.of(hosts));
        c.put("discovery.max.pages",3);c.put("discovery.max.depth",2);c.put("discovery.max.links",20);return c;
    }
    @Test public void rejectedScopeIsCountedWithoutPersistingRawCredentials()throws Exception {
        DiscoveryScopeFilter f=new DiscoveryScopeFilter();f.configure(config(host(1)),new ObjectMapper().createObjectNode(),"audit");
        Metadata parent=new Metadata();assertNull(f.filter(null,parent,"http://user:password@outside.invalid/"));
        assertEquals("1",parent.getFirstValue("discovery.scope.rejected"));
        assertFalse(parent.toString().contains("password"));assertFalse(parent.toString().contains("outside.invalid"));
    }
    @Test public void durableBudgetModeFailsClosed()throws Exception {
        Config c=config(host(1));c.remove("discovery.budget.mode");
        assertThrows(IllegalArgumentException.class,()->new DiscoveryParseFilter().configure(c,new ObjectMapper().createObjectNode(),"M3"));
    }
    @Test public void htmlChainCyclesDuplicateAndMetadataOnly()throws Exception {
        String a="http://"+host(1)+"/",b="http://"+host(2)+"/",c="https://"+host(3)+"/";
        DiscoveryPlanner p=new DiscoveryPlanner(config(host(1),host(2),host(3)),3,2,20);
        var refs=Jsoup.parse("<a href='"+b+"'>B</a><a href='"+b+"#dup'>dup</a><a href='/data.zip'>zip</a><a href='http://invalid.onion/'>bad</a>",a).select("a[href]").eachAttr("href");
        var first=p.discover(a,0,refs);
        assertEquals(List.of("ADMITTED","DUPLICATE","METADATA_ONLY","REJECTED"),first.stream().map(DiscoveryPlanner.Edge::getResult).toList());
        assertEquals("ADMITTED",p.discover(b,1,List.of(c)).get(0).getResult());
        assertEquals("DUPLICATE",p.discover(c,2,List.of(a)).get(0).getResult());
        assertNotNull(first.get(0).getDiscoveredAt());
    }
    @Test public void relativeLinksDepthPageAndLinkBounds()throws Exception {
        String h=host(1),base="http://"+h+"/dir/a";
        var p=new DiscoveryPlanner(config(h),2,1,3);
        var result=p.discover(base,0,List.of("../b","/c","/dump.sql","/not-recorded"));
        assertEquals("http://"+h+"/b",result.get(0).getTarget());
        assertEquals("PAGE_LIMIT",result.get(1).getResult());assertEquals(3,result.size());
        assertEquals("METADATA_ONLY",result.get(2).getResult());
        assertEquals("DEPTH_LIMIT",new DiscoveryPlanner(config(h),10,0,5).discover(base,0,List.of("/c")).get(0).getResult());
    }
    @Test public void replayDoesNotLosePreviouslyAdmittedButUnacknowledgedOutlink()throws Exception {
        String h=host(1),source="http://"+h+"/";
        DiscoveryParseFilter filter=new DiscoveryParseFilter();filter.configure(config(h),new ObjectMapper().createObjectNode(),"M3");
        for(int attempt=0;attempt<2;attempt++) {
            ParseResult r=new ParseResult(new ArrayList<>(List.of(new Outlink(source+"a"),new Outlink(source+"a#duplicate"))));r.set(source,new Metadata());
            filter.filter(source,new byte[0],null,r);
            assertEquals(1,r.getOutlinks().size());
            assertEquals(source+"a",r.getOutlinks().get(0).getTargetURL());
        }
    }

    @Test public void parseFilterKeepsProvenanceAndRemovesNonFetchLinks()throws Exception {
        String h=host(1),source="http://"+h+"/";ParseResult r=new ParseResult(new ArrayList<>(List.of(new Outlink(source+"a"),new Outlink(source+"db.sqlite"))));
        r.set(source,new Metadata());DiscoveryParseFilter filter=new DiscoveryParseFilter();
        filter.configure(config(h),new ObjectMapper().createObjectNode(),"M3");filter.filter(source,new byte[0],null,r);
        assertEquals(1,r.getOutlinks().size());assertEquals(source,r.getOutlinks().get(0).getMetadata().getFirstValue("discovery.source"));
        assertEquals(2,r.get(source).getMetadata().getValues("discovery.edges").length);
        assertEquals("1",r.getOutlinks().get(0).getMetadata().getFirstValue("discovery.depth"));
    }
}
