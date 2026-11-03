package dev.onionall.brawler.cli;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class InteractiveSessionTest {
    private static final Map<String,String> ENV=Map.of("TERM","xterm-256color","COLORTERM","truecolor");
    private static String plain(String value) {return value.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]","");}
    private static int count(String text,String value){return text.split(Pattern.quote(value),-1).length-1;}
    @Test public void geometryIsSingleColumnAndFitsAllThreeLayoutsAndSmallScreens() {
        for(int width:List.of(8,40,69,70,80,100,120)) for(int rows:List.of(8,24,40)) {
            var header=Brand.header(width,rows,ENV,true);
            assertTrue(header.size()<=Math.max(1,rows-6));
            for(String line:header) {String p=plain(line);assertTrue(p.length()<width);assertTrue(p.chars().allMatch(c->c>=32&&c<127));}
        }
        assertTrue(String.join("\n",Brand.header(120,30,ENV,true)).contains("38;2;"));
        assertTrue(String.join("\n",Brand.header(80,24,Map.of("TERM","xterm-256color"),true)).contains("38;5;"));
        assertTrue(String.join("\n",Brand.header(80,24,Map.of("TERM","vt100"),true)).contains("[35m"));
        assertFalse(String.join("\n",Brand.header(80,24,Map.of("NO_COLOR","1"),true)).contains("\u001b"));
    }
    @Test public void mascotFooterHasThreeBlankRowsAndNoDuplicateName() {
        var header=Brand.header(120,40,ENV,true).stream().map(InteractiveSessionTest::plain).toList();
        int subtitle=header.indexOf("Tor-native fetch engine");
        assertTrue(subtitle>=3);
        assertEquals(List.of("", "", ""),header.subList(subtitle-3,subtitle));
        assertFalse(header.contains("ONIONALL BRAWLER"));
        assertEquals("=".repeat(97),header.get(subtitle+1));
    }
    @Test public void depthGlyphsHaveSameGeometryAndExplicitAsciiFallback() {
        var env=new HashMap<>(ENV);env.put("LANG","en_US.UTF-8");
        var filled=Brand.header(80,30,env,true);
        assertTrue(String.join("",filled).contains("\u2588"));
        env.put("ONIONALL_ASCII","1");var ascii=Brand.header(80,30,env,true);
        assertEquals(ascii.size(),filled.size());
        for(int row=0;row<ascii.size();row++)assertEquals(plain(ascii.get(row)).length(),plain(filled.get(row)).length());
        assertTrue(plain(String.join("",ascii)).chars().allMatch(c->c>=32&&c<127));
    }
    @Test public void headerIsNotRepaintedByNavigationOrProgressAndCleanupIsIdempotent() {
        StringWriter capture=new StringWriter();var out=new PrintWriter(capture,true);
        var size=new AtomicReference<>(new InteractiveSession.Size(80,24));
        var session=new InteractiveSession(out,ENV,true,size::get,false);
        int headerRows=Brand.header(80,24,ENV,true).size();int boundary=capture.toString().length();
        session.begin();session.writer().println("Menu");session.begin();session.progress("Working 1s");
        session.progress("Working 2s");session.stopProgress();session.writer().println("Result");
        String updates=capture.toString().substring(boundary);
        var positions=Pattern.compile("\u001b\\[(\\d+);\\d+H").matcher(updates);
        while(positions.find())assertTrue(Integer.parseInt(positions.group(1))>headerRows);
        assertEquals(1,count(capture.toString(),"Tor-native fetch engine"));
        assertEquals(1,count(capture.toString(),"\u001b[?1049h"));
        session.close();String closed=capture.toString();session.close();assertEquals(closed,capture.toString());
        assertTrue(closed.contains("\u001b[r\u001b[?25h"));
    }
    @Test public void resizeOnlyReflowsOnDimensionChangeAndPlainModeHasNoControls() {
        StringWriter capture=new StringWriter();var size=new AtomicReference<>(new InteractiveSession.Size(120,30));
        try(var session=new InteractiveSession(new PrintWriter(capture,true),ENV,true,size::get,false)) {
            session.resize();assertEquals(1,count(capture.toString(),"Tor-native fetch engine"));
            size.set(new InteractiveSession.Size(40,20));session.resize();session.resize();
            assertEquals(2,count(capture.toString(),"Tor-native fetch engine"));
            assertEquals(1,count(capture.toString(),"\u001b[?1049h"));
        }
        capture=new StringWriter();
        try(var session=new InteractiveSession(new PrintWriter(capture,true),Map.of("NO_COLOR","1"),false,size::get,false)) {
            session.begin();session.writer().println("plain");session.begin();session.writer().println("next");
        }
        assertFalse(capture.toString().contains("\u001b"));assertEquals(1,count(capture.toString(),"Tor-native fetch engine"));
        assertEquals("\\u4e2d",InteractiveSession.clip("中",20));
    }
}
