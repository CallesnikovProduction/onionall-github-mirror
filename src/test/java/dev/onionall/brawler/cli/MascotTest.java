package dev.onionall.brawler.cli;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class MascotTest {
    @Test public void vectorHasTransparentCornersLightFaceAndDarkExpression() {
        var image=Mascot.raster(100,125);
        assertEquals(0,image.getRGB(0,0)>>>24);
        assertEquals(0,image.getRGB(99,124)>>>24);
        int face=image.getRGB(52,82),eye=image.getRGB(42,86);
        assertEquals(255,face>>>24);assertEquals(255,eye>>>24);
        assertTrue(((face>>16)&255)>200);assertTrue(((eye>>16)&255)<80);
    }
    @Test public void colouredSurfaceUsesOnlyHalfBlocksAndResetsBackground() {
        var lines=Mascot.render(28,17,Map.of("LANG","en_US.UTF-8","COLORTERM","truecolor"),true);
        assertEquals(17,lines.size());
        for(String line:lines) {
            assertTrue(line.endsWith("\u001b[0m"));
            String text=line.replaceAll("\u001b\\[[0-9;]*m","");
            assertEquals(28,text.length());
            assertTrue(text.chars().allMatch(c->c==' '||c=='▀'||c=='▄'));
        }
        assertTrue(String.join("",lines).contains("[48;2;"));
    }
}
