package dev.onionall.brawler.cli;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

final class HtmlFiles {
    static final long INSPECT_LIMIT = 4 * 1024 * 1024;
    private HtmlFiles() { }
    static Document parse(byte[] content, String base) throws IOException {
        return Jsoup.parse(new ByteArrayInputStream(content), null, base);
    }
    static Map<String,Object> inspect(Path file) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) { bytes = in.readNBytes((int)INSPECT_LIMIT + 1); }
        if (bytes.length > INSPECT_LIMIT) throw new IOException("Inspection limited to 4 MiB");
        Document doc = parse(bytes, "");
        var anchors = doc.select("a[href]"); Set<String> links = new HashSet<>();
        int absolute = 0, relative = 0, onion = 0;
        for (var a : anchors) {
            String href = a.attr("href"); links.add(href);
            try {
                URI uri = URI.create(href);
                if (uri.isAbsolute() || uri.getRawAuthority() != null) absolute++; else relative++;
                if (uri.getHost() != null && uri.getHost().toLowerCase(Locale.ROOT).endsWith(".onion")) onion++;
            } catch (IllegalArgumentException e) { relative++; }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("file", file.toAbsolutePath().toString());result.put("bytes", bytes.length);
        result.put("title", doc.title()); result.put("encoding", doc.charset().name());
        result.put("anchors", anchors.size());result.put("unique_links", links.size());
        result.put("absolute_links", absolute);result.put("relative_links", relative);result.put("onion_links", onion);
        var meta = doc.selectFirst("meta[name=description]");result.put("description", meta == null ? "" : meta.attr("content"));
        return result;
    }
    static void save(byte[] bytes, Path target, boolean overwrite) throws IOException {
        Path absolute = target.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), ".onionall-", ".part");
        try {
            Files.write(temp, bytes);
            if (overwrite) Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else {
                // Same-directory hard link publishes a complete file atomically and cannot replace a racing writer.
                Files.createLink(absolute, temp);
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
