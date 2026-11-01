package dev.onionall.brawler.tor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.util.List;
import org.apache.storm.Config;
import org.apache.stormcrawler.Metadata;
import org.junit.Test;
import static org.junit.Assert.*;

/** Filter is pure policy: these tests open no sockets. */
public class TorScopeFilterTest {
    private TorScopeFilter filter(String host) throws Exception {
        TorScopeFilter filter=new TorScopeFilter();
        filter.configure(TorPolicyTest.configuration(host,9050),new ObjectMapper().createObjectNode(),"scope");
        return filter;
    }
    @Test public void nullMetadataStillEnforcesScopeAndSourceDoesNotGrantAuthority() throws Exception {
        String host=TorPolicyTest.onion(3);TorScopeFilter filter=filter(host);
        assertEquals("http://"+host+"/",filter.filter(null,null,"http://"+host));
        URL foreign=new URL("http://outside.invalid/");
        assertEquals("http://"+host+"/a",filter.filter(foreign,null,"http://"+host+"/a"));
        for(String target:List.of("http://outside.invalid/","http://"+host+":81/","http://u@"+host+"/","http:opaque","%","http://"+host+"%2e/"))
            assertNull(filter.filter(new URL("http://"+host+"/"),null,target));
        Metadata metadata=new Metadata();metadata.setValue("HTTP.proxy","untrusted");
        assertNull(filter.filter(null,metadata,"http://"+host+"/"));
    }
    @Test public void wireDotSegmentsCannotBypassRobotsPath() throws Exception {
        String host=TorPolicyTest.onion(3);TorScopeFilter filter=filter(host);
        for(String path:List.of("/safe/%2e%2e/blocked","/safe/.%2E/blocked","/safe/%2e./blocked","/../blocked","/..","/%2e/blocked","/safe/../blocked","//blocked","/./blocked"))
            assertNull(path,filter.filter(null,null,"http://"+host+path));
        assertNull(filter.filter(null,null,"http://"+host+"/safe/../blocked"));
    }

    @Test public void badConfigurationAndUseBeforeConfigureFailFast() throws Exception {
        String host=TorPolicyTest.onion(3);
        assertThrows(RuntimeException.class,()->new TorScopeFilter().filter(null,null,"http://"+host));
        Config invalid=TorPolicyTest.configuration(host,9050);invalid.put("tor.allowed.hosts",List.of());
        assertThrows(IllegalArgumentException.class,()->new TorScopeFilter().configure(invalid,new ObjectMapper().createObjectNode(),"scope"));
    }
    @Test public void encodedPathQueryArePreservedAndNormalizationIsIdempotent() throws Exception {
        String host=TorPolicyTest.onion(3);TorScopeFilter filter=filter(host);
        String target="http://"+host+"/part%2ename/%2f?q=%2F#ignored";
        String normalized=filter.filter(null,null,target);
        assertEquals("http://"+host+"/part%2ename/%2f?q=%2F",normalized);
        assertEquals(normalized,filter.filter(null,null,normalized));
    }
}
