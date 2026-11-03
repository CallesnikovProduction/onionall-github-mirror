package dev.onionall.brawler.archive;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Discovery sources expose their actual capabilities; assisted search never scrapes. */
public interface SearchProvider {
    Map<String,Object> search(String query, int limit) throws IOException;

    final class LocalArchive implements SearchProvider {
        private final Path root;
        private final String job;
        public LocalArchive(Path root, String job) { this.root = root; this.job = job; }
        public Map<String,Object> search(String query, int limit) throws IOException {
            try (var store = new ArchiveStore(root)) { return store.search(query, job, limit); }
        }
    }

    final class AssistedAhmia implements SearchProvider {
        public Map<String,Object> search(String query, int limit) throws IOException {
            if (query == null || query.isBlank() || query.length() > 256 || limit < 1 || limit > 100)
                throw new IOException("Require nonblank query up to256 characters and limit1..100");
            return Map.of("provider", "ahmia", "status", "USER_ACTION_REQUIRED",
                "network_requested", false, "programmatic_search", false,
                "official_url", "https://ahmia.fi/search/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
                "instruction", "Open the official page yourself and import manually selected permitted URLs with --provider ahmia; no results have been downloaded");
        }
    }
}
