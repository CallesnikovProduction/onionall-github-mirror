package dev.onionall.brawler.research;

import dev.onionall.brawler.tools.OnionSmoke;
import dev.onionall.brawler.tor.UriReferences;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Проверяет отдельную thread identity; не меняет общий fetch URL contract.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class ForumPaginationTest {
    private static final String ROOT = "http://" + OnionSmoke.HOST;
    @Test public void knownUniqueQueriesDeduplicateButRetainPageAndThreadIdentity() {
        String seed = ROOT + "/topic?id=123&page=1";
        assertEquals(ROOT + "/topic?id=123&page=2", ForumPagination.canonical(ROOT + "/topic?page=2&id=123#reply"));
        assertEquals(ForumPagination.identity(seed), ForumPagination.identity(ROOT + "/topic?page=3&id=123"));
        assertNotEquals(ForumPagination.identity(seed), ForumPagination.identity(ROOT + "/topic?id=124&page=1"));
        assertNotEquals(ForumPagination.canonical(seed), ForumPagination.canonical(ROOT + "/topic?id=123&page=2"));
        assertEquals(ROOT + "/topic?id=123&page=2", ForumPagination.eligible(seed, seed, ROOT + "/topic?page=2&id=123"));
    }
    @Test public void ambiguousUnknownAndEncodedQueryDataAreNotRewritten() {
        for (String query : List.of("page=2&id=123&id=456", "signature=x&page=2&id=123", "page=%32&id=123", "id=a%26b&page=2")) {
            String input = ROOT + "/topic?" + query;
            if (!query.equals("id=a%26b&page=2")) assertEquals(input, ForumPagination.canonical(input));
            else assertTrue(ForumPagination.canonical(input).contains("id=a%26b"));
        }
        String seed=ROOT+"/topic?id=123";
        assertNull(ForumPagination.eligible(seed,seed,ROOT+"/topic?page=2&id=123&id=456"));
        assertNull(ForumPagination.eligible(seed,seed,ROOT+"/topic?signature=x&page=2&id=123"));
        assertNull(ForumPagination.eligible(seed,seed,ROOT+"/topic?id=123&page=garbage"));
    }
    @Test public void pathPaginationCannotExpandIntoOtherTopicsHostsOrWordMatches() {
        String seed=ROOT+"/thread/alpha/";
        assertEquals(ROOT+"/thread/alpha/page/2/",ForumPagination.eligible(seed,seed,ROOT+"/thread/alpha/page/2/"));
        assertEquals(ROOT+"/thread/alpha/page/2/",ForumPagination.eligible(ROOT+"/thread/alpha",ROOT+"/thread/alpha",ROOT+"/thread/alpha/page/2/"));
        for (String url : List.of(ROOT+"/thread/beta/page/2/", ROOT+"/pageant/2/", "https://"+OnionSmoke.HOST+"/thread/alpha/page/2/", "javascript:alert(1)", ROOT+"/thread/alpha/?other=page")) assertNull(ForumPagination.eligible(seed,seed,url));
    }
    @Test public void emptyPathReferencesKeepCurrentDocumentPathAndRawQuery() {
        String source=ROOT+"/forum/thread?id=123&page=1";
        assertEquals(ROOT+"/forum/thread?id=123&page=2",UriReferences.resolve(source,"?id=123&page=2").toString());
        assertEquals(source+"#p1",UriReferences.resolve(source,"#p1").toString());
        assertEquals(source,UriReferences.resolve(source,"").toString());
        assertEquals(ROOT+"/forum/thread?",UriReferences.resolve(source,"?").toString());
        assertEquals(ROOT+"/forum/next?page=2",UriReferences.resolve(source,"next?page=2").toString());
    }
    @Test public void actualDomEntitiesAndNextLinkAreObservedWithoutNetwork() {
        String seed=ROOT+"/topic?id=123&page=1";
        var rows=ForumPagination.observations(seed,("<a rel=next href='?page=2&amp;id=123'>Next</a>"+
            "<a href='?id=123&#38;page=2#post'>2</a><a href='/other/page/2/'>other</a>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1,rows.size());
        assertEquals(ROOT+"/topic?id=123&page=2",rows.getFirst().get("target_url"));
    }
    @Test public void threadRuntimeContractCannotExceedIndependentPageAndHopBudgets() {
        var conf=ForumPagination.configuration(Map.of("provider","m61:thread","seeds",List.of(ROOT+"/topic?id=123"),"max_pages",5,"depth",4));
        assertEquals(5,conf.get("research.thread.max.pages"));
        assertThrows(IllegalArgumentException.class,()->ForumPagination.configuration(Map.of("provider","m61:thread","seeds",List.of(ROOT+"/topic?id=123"),"max_pages",6,"depth",5)));
        assertThrows(IllegalArgumentException.class,()->ForumPagination.configuration(Map.of("provider","m61:thread","seeds",List.of(ROOT+"/topic?id=123"),"max_pages",5,"depth",0)));
    }
}
