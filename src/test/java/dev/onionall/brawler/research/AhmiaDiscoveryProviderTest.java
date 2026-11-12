package dev.onionall.brawler.research;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Проверки разрешённой HTML-формы и parser, не доказательство live Ahmia/Tor.
 * Fixtures повторяют наблюдавшуюся структуру официального HTML без реальных dataset rows.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
public class AhmiaDiscoveryProviderTest {
    private static final String ORIGIN = AhmiaDiscoveryProvider.DEFAULT_ORIGIN;
    private static final String HOST = "aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion";
    private static final String URL = "http://" + HOST + "/";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private static final String FORM = "<html><form id='searchForm' action='/search/' method='get'>"
        + "<input name='q'><input type='hidden' name='ab1234' value='cdef01'></form></html>";

    private static String results(String entries) {
        return "<html><a href='" + URL + "navigation'>not a search result</a>"
            + "<div id='ahmiaResultsPage'><ol class='searchResults'>" + entries + "</ol></div></html>";
    }

    private static String entry(String target, String title) {
        return "<li class='result'><h4><a href='" + target.replace("&", "&amp;") + "'>" + title
            + "</a></h4><p>Observed snippet</p><cite>display hostname is not trusted</cite></li>";
    }

    private static String wrapper(String target) {
        return "/search/redirect?search_term=Kotlin multiplatform&redirect_url=" + target;
    }

    private static ProviderResponse response(String body, String url) {
        return new ProviderResponse(200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), url);
    }

    private static AhmiaDiscoveryProvider provider(String html, List<String> requested) {
        return new AhmiaDiscoveryProvider(url -> {
            requested.add(url);
            return response(requested.size() == 1 ? FORM : html, url);
        }, CLOCK);
    }

    @Test public void submitsObtainedFormAndPreservesRawTargetQueriesAndProvenance() throws Exception {
        String first = wrapper(URL + "project?language=Kotlin&sort=latest+activity%2Bdesc#section");
        String same = "http://" + HOST + ":80/project?language=Kotlin&sort=latest+activity%2Bdesc";
        var requests = new ArrayList<String>();
        var batch = provider(results(entry(first, "Genuine title") + entry(same, "Duplicate")), requests)
            .discover("  Kotlin   & Java+  ", 20);
        assertEquals(2, requests.size());
        assertEquals(ORIGIN, requests.getFirst());
        assertEquals(ORIGIN + "search/?q=Kotlin+%26+Java%2B&ab1234=cdef01", requests.getLast());
        assertEquals("Kotlin & Java+", batch.getNormalizedQuery());
        assertEquals(2, batch.getActualResultCount());
        assertEquals(1, batch.getCandidates().size());
        var candidate = batch.getCandidates().getFirst();
        assertEquals(URL + "project?language=Kotlin&sort=latest+activity%2Bdesc", candidate.getCanonicalUrl());
        assertEquals("Genuine title", candidate.getTitle());
        assertEquals("Observed snippet", candidate.getSnippet());
        assertEquals(first, candidate.getOriginalResultUrl());
        assertEquals(List.of(first, same), candidate.getProvenance());
        assertEquals(HOST, candidate.getDuplicateGroup());
        assertEquals("2026-11-11T12:00:00Z", candidate.getDiscoveredAt());
        assertEquals("DUPLICATE", batch.getRejected().getFirst().get("validation_status"));
        assertFalse(batch.getResponseUrl().contains("cdef01"));
        assertEquals(false, batch.toMap().get("automatic_crawl_authorization"));
        assertEquals(candidate.getCandidateId(), candidate.toMap().get("candidate_id"));
    }

    @Test public void decodesOnlyOnceForAnActuallyEncodedWrapper() throws Exception {
        String target = URL + "path%2Fpart?q=a+b&x=%2B";
        String encoded = URLEncoder.encode(target, StandardCharsets.UTF_8);
        var batch = provider(results(entry(wrapper(encoded), "Encoded")), new ArrayList<>()).discover("Kotlin", 1);
        assertEquals(target, batch.getCandidates().getFirst().getCanonicalUrl());
        String twice = URLEncoder.encode(encoded, StandardCharsets.UTF_8);
        assertTrue(provider(results(entry(wrapper(twice), "Twice")), new ArrayList<>())
            .discover("Kotlin", 1).getCandidates().isEmpty());
    }

    @Test public void refusesUnsafeTargetsAndUnrelatedRedirectWrappersWithoutVisitingThem() throws Exception {
        var requests = new ArrayList<String>();
        String html = results(entry("http://user@" + HOST + "/", "userinfo")
            + entry("http://127.0.0.1/private", "local")
            + entry("http://" + "a".repeat(56) + ".onion/", "invalid checksum")
            + entry("ftp://" + HOST + "/", "scheme")
            + entry("http://example.org/search/redirect?redirect_url=" + URL, "unrelated wrapper")
            + entry(wrapper(URL + "?x=y&redirect_url=" + URL), "ambiguous wrapper"));
        var batch = provider(html, requests).discover("Kotlin", 20);
        assertEquals(6, batch.getActualResultCount());
        assertTrue(batch.getCandidates().isEmpty());
        assertEquals(6, batch.getRejected().size());
        assertEquals(2, requests.size());
    }

    @Test public void respectsMaximumAndReportsObservedCountRatherThanManufacturingTwenty() throws Exception {
        var batch = provider(results(entry(URL + "one", "One") + entry(URL + "two", "Two")), new ArrayList<>())
            .discover("Kotlin", 1);
        assertEquals(1, batch.getCandidates().size());
        assertEquals(2, batch.getActualResultCount());
        assertEquals(2, batch.getValidUniqueCount());
        assertEquals("SINGLE_PAGE_LIMIT_REACHED", batch.getPaginationState());
        var few = provider(results(entry(URL, "Only one")), new ArrayList<>()).discover("Kotlin", 20);
        assertEquals(1, few.getCandidates().size());
        var empty = provider("<html><div id='ahmiaResultsPage'><p id='noResults'>No results</p></div></html>",
            new ArrayList<>()).discover("Kotlin", 20);
        assertEquals(0, empty.getActualResultCount());
        assertTrue(empty.getCandidates().isEmpty());
    }

    @Test public void doesNotGuessUnverifiedPaginationOrFollowItsLinks() throws Exception {
        var requests = new ArrayList<String>();
        String html = results(entry(URL, "One")) + "<a rel='next' href='/search/?page=1'>next</a>";
        var batch = provider(html, requests).discover("Kotlin", 20);
        assertEquals(2, requests.size());
        assertEquals("PAGINATION_NOT_SUPPORTED", batch.getPaginationState());
    }

    @Test public void rejectsInvalidQueriesAndLimitsBeforeAnyNetworkRequest() {
        AhmiaDiscoveryProvider provider = new AhmiaDiscoveryProvider(url -> { throw new AssertionError("No network"); });
        for (String query : List.of(" ", "x".repeat(101), "one ".repeat(11), "Kotlin\u0000bad")) {
            assertThrows(IllegalArgumentException.class, () -> provider.discover(query, 20));
        }
        assertThrows(IllegalArgumentException.class, () -> provider.discover("Kotlin", 0));
        assertThrows(IllegalArgumentException.class, () -> provider.discover("Kotlin", 21));
    }

    @Test public void treatsProviderErrorsMissingFormOrSearchStructureAsErrorsNotEmptyResults() {
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url ->
            new ProviderResponse(429, "text/html", "limited".getBytes(StandardCharsets.UTF_8), url))
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url -> response("<html>challenge</html>", url))
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> provider("<html>homepage instead of results</html>", new ArrayList<>())
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url ->
            response(FORM.replace("action='/search/'", "action='http://127.0.0.1/search/'"), url))
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url ->
            new ProviderResponse(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), url))
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url ->
            new ProviderResponse(200, "text/html", new byte[1048577], url))
            .discover("Kotlin", 20));
        assertThrows(IOException.class, () -> new AhmiaDiscoveryProvider(url ->
            response(FORM, "http://127.0.0.1/"))
            .discover("Kotlin", 20));
    }

    @Test public void propagatesTransportCancellationAndDoesNotStartMoreRequests() {
        var requests = new ArrayList<String>();
        AhmiaDiscoveryProvider provider = new AhmiaDiscoveryProvider(url -> {
            requests.add(url);
            throw new InterruptedIOException("cancelled fixture request");
        });
        assertThrows(InterruptedIOException.class, () -> provider.discover("Kotlin", 20));
        assertEquals(1, requests.size());
    }
}
