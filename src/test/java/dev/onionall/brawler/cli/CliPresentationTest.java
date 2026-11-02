package dev.onionall.brawler.cli;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CliPresentationTest {
    private static final Map<String,Object> RESULT=Map.of("socks_tcp_reachable",true,"socks_endpoint","127.0.0.1:19050",
            "downloads","/tmp/downloads","java","25.0.5","timeout_ms",30000,"max_bytes",131072);
    @Test public void shortcutsHaveDistinctSemanticColorsWithoutChangingLabels() {
        for(String row:new String[]{"(1)  Fetch onion page","(2)  Inspect HTML","(3)  Tor SOCKS status","(4)  Help","(0)  Quit"}) {
            assertEquals(row,CliPresentation.style(row,false));
            String styled=CliPresentation.style(row,true);
            assertEquals(row,styled.replaceAll("\u001b\\[[0-9;]*m",""));
            assertTrue(styled.startsWith("\u001b[95m("));
            assertTrue(styled.endsWith("\u001b[0m"));
        }
        assertTrue(CliPresentation.style("(1)  Fetch onion page",true).contains("[32m"));
        assertTrue(CliPresentation.style("(0)  Quit",true).contains("[91m"));
    }
    @Test public void statusFieldsStayAlignedAndExplicitlyTcpOnly() {
        var lines=CliPresentation.status(RESULT,false);
        assertEquals("TOR CONNECTION STATUS",lines.getFirst());
        String plain=String.join("\n",lines);
        assertTrue(plain.contains("Tor SOCKS     : TCP reachable"));
        assertTrue(plain.contains("Tor / onion   : NOT_TESTED (TCP reachability only)"));
        for(String line:lines)if(line.contains(":"))assertEquals(14,line.indexOf(':'));
        assertFalse(plain.contains("\u001b"));
        assertTrue(CliPresentation.style(lines.get(3),true).contains("[32m"));
        assertEquals(5,CliPresentation.status(RESULT,true).size());
        assertEquals("Choose ❯ ",InteractiveSession.clip("Choose ❯ ",20));
        assertFalse(InteractiveSession.clip("\u001b[31mfoo",20).contains("\u001b"));
    }
}
