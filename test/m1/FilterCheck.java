package dev.onionall.brawler.m1;

import java.net.URL;
import java.util.Map;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.filtering.URLFilters;

/** Exercise the real StormCrawler filters, including hostile authority/path forms. */
public final class FilterCheck {
    public static void main(String[] args) throws Exception {
        var filters = URLFilters.fromConf(Map.of("urlfilters.config.file", "m1-urlfilters.json"));
        URL source = new URL("http://fixture:8080/source");
        for (String suffix : new String[]{"", "a", "b", "blocked", "missing", "pending"}) {
            String candidate = "http://fixture:8080/" + suffix;
            if (!candidate.equals(filters.filter(source, new Metadata(), candidate)))
                throw new AssertionError("Allowed fixture URL rejected: " + candidate);
        }
        String normalized = filters.filter(source, new Metadata(), "http://fixture:8080/a#duplicate");
        if (!"http://fixture:8080/a".equals(normalized)) throw new AssertionError("Fragment was not removed");
        for (String rejected : new String[]{
                "http://frontier:7071/", "http://outside.invalid/", "http://127.0.0.1/",
                "http://fixture:8080.evil.invalid/a", "http://fixture:8081/a", "https://fixture:8080/a",
                "http://evil@fixture:8080/a", "http://fixture:8080/a?x=1", "http://fixture:8080/unknown",
                "http://fixture:8080/%2e%2e/a", "file:///etc/passwd"}) {
            if (filters.filter(source, new Metadata(), rejected) != null)
                throw new AssertionError("Out-of-scope URL accepted: " + rejected);
        }
        filters.cleanup();
        System.out.println("M1 filter checks passed (actual StormCrawler URLFilters)");
    }
}
