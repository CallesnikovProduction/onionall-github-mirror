package dev.onionall.brawler.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tools.OnionSmoke;
import dev.onionall.brawler.tor.TorPolicyTest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryReportTest {
    static final String BASE="http://"+OnionSmoke.HOST+"/dir/page?old=1";
    private static Map<String,Object> config(){return Map.of("tor.mode","ONLY","tor.socks.host","127.0.0.1","tor.socks.port",19050,"tor.allowed.hosts",List.of(OnionSmoke.HOST));}
    private static Path html(String body)throws IOException {Path f=Files.createTempFile("discovery-", ".html");Files.writeString(f,body);return f;}
    private static String anchor(String ref){return "<a href='"+ref+"'>link</a>";}
    private static String other()throws Exception {var method=TorPolicyTest.class.getDeclaredMethod("onion",int.class);method.setAccessible(true);return (String)method.invoke(null,42);}
    @Test public void relativeCaseDefaultPortQueryFragmentsAndDuplicates()throws Exception {
        Path f=html(anchor("child#one")+anchor("child#two")+anchor("#self")+anchor("?q=ABC%2f")
                +anchor("HTTP://"+OnionSmoke.HOST.toUpperCase(Locale.ROOT)+":80/dir/child")+anchor("//"+OnionSmoke.HOST+"/Next"));
        try {
            var r=DiscoveryReport.discover(f,BASE,config());
            assertEquals(6,r.links_found());assertEquals(4,r.unique());assertEquals(2,r.duplicates());
            assertEquals(4,r.in_scope());assertEquals(0,r.rejected());
            assertEquals("http://"+OnionSmoke.HOST+"/dir/child",r.links().get(0).normalized_target());
            assertEquals(BASE,r.links().get(2).normalized_target());
            assertEquals("http://"+OnionSmoke.HOST+"/dir/page?q=ABC%2f",r.links().get(3).normalized_target());
            assertTrue(r.links().get(5).normalized_target().endsWith("/Next"));
            assertTrue(r.links().stream().noneMatch(DiscoveryReport.Link::admitted));
        }finally{Files.delete(f);}
    }
    @Test public void excludesUnknownOnionAndRejectsUnsafeTargetsWithoutExpandingScope()throws Exception {
        Path f=html(anchor("http://"+other()+"/")+anchor("https://example.com/")+anchor("mailto:a@b")
                +anchor("http://invalid.onion/")+anchor("http://"+OnionSmoke.HOST+":81/")
                +anchor("http://user:secret@"+OnionSmoke.HOST+"/")+anchor("/%2e%2e/private")
                +anchor("bad%ZZ")+anchor("data.zip")+anchor("http://[broken"));
        try {
            var r=DiscoveryReport.discover(f,BASE,config());assertEquals(10,r.links_found());
            assertEquals(1,r.out_of_scope());assertEquals(1,r.in_scope());assertEquals(8,r.rejected());
            assertTrue(r.links().get(1).syntactically_valid());assertFalse(r.links().get(1).onion_v3_valid());
            assertTrue(r.links().getFirst().onion_v3_valid());
            assertFalse(r.links().getFirst().eligible_for_admission());assertFalse(r.links().getFirst().in_scope());
            assertEquals("METADATA_ONLY",r.links().get(8).reason());assertFalse(r.links().get(8).eligible_for_admission());
        }finally{Files.delete(f);}
    }
    @Test public void ignoresDocumentBaseAndRefreshAndNeverExecutesScripts()throws Exception {
        Path f=html("<base href='http://example.com/'><meta http-equiv='refresh' content='0;url=http://example.com/'><script>fetch('http://example.com')</script>"+anchor("child"));
        try {var r=DiscoveryReport.discover(f,BASE,config());assertEquals(1,r.links_found());assertTrue(r.links().getFirst().in_scope());}
        finally{Files.delete(f);}
    }
    @Test public void limitsInputAndLinksAndRejectsOutOfScopeSource()throws Exception {
        Path f=html(anchor("child").repeat(1002));
        try {var r=DiscoveryReport.discover(f,BASE,config());assertTrue(r.truncated());assertEquals(1000,r.processed());assertEquals(1,r.unique());
            assertThrows(IOException.class,()->DiscoveryReport.discover(f,"http://"+other()+"/",config()));
            assertThrows(IOException.class,()->DiscoveryReport.discover(f.getParent(),BASE,config()));
            Files.write(f,new byte[(int)HtmlFiles.INSPECT_LIMIT+1]);assertThrows(IOException.class,()->DiscoveryReport.discover(f,BASE,config()));
        }finally{Files.delete(f);}
    }
    @Test public void cliJsonIsDeterministicOfflineAndExportIsAtomicNoClobber()throws Exception {
        Path f=html(anchor("child")+anchor("child#dup")), export=f.resolveSibling(f.getFileName()+".jsonl");
        try {
            String previous=null;
            for(int i=0;i<2;i++) {
                StringWriter out=new StringWriter(),err=new StringWriter();
                assertEquals(0,Onionall.execute(new String[]{"discover",f.toString(),"--base-url",BASE,"--json"},new PrintWriter(out,true),new PrintWriter(err,true),false,Map.of(),(c,u)->{throw new AssertionError("No network");}));
                assertEquals("",err.toString());assertFalse(out.toString().contains("\u001b"));
                assertEquals(0,new ObjectMapper().readTree(out.toString()).get("network_requests").asInt());
                if(previous!=null)assertEquals(previous,out.toString());previous=out.toString();
            }
            String[] args={"discover",f.toString(),"--base-url",BASE,"--export",export.toString(),"--json"};
            var out=new PrintWriter(new StringWriter());
            assertEquals(0,Onionall.execute(args,out,out,false,Map.of(),(c,u)->{throw new AssertionError();}));
            assertEquals(2,Files.readAllLines(export).size());String saved=Files.readString(export);
            assertEquals(6,Onionall.execute(args,out,out,false,Map.of(),(c,u)->{throw new AssertionError();}));assertEquals(saved,Files.readString(export));
        }finally{Files.deleteIfExists(export);Files.delete(f);}
    }
}
