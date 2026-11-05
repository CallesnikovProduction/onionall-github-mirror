package dev.onionall.brawler.cli;

import dev.onionall.brawler.tor.TorPolicy;
import dev.onionall.brawler.discovery.DiscoveryPlanner;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * <b>Ограниченный offline-отчёт о ссылках HTML.</b>
 *
 * <p>Использует TorPolicy и metadata-only правило DiscoveryPlanner, не создаёт transport или Frontier-клиент и не подтверждает admission.
 */
final class DiscoveryReport {

    static final int MAX_LINKS = 1000;

    static final int MAX_URL = 2048;

    private DiscoveryReport() {
    }

    record Link(String source, String url, String normalized_target, boolean syntactically_valid,
        boolean onion_v3_valid, boolean in_scope, boolean eligible_for_admission, boolean admitted, String reason) {
    }

    record Report(String source, String base_url, int links_found, int processed, boolean truncated, int unique,
        int duplicates, int in_scope, int out_of_scope, int rejected, Map<String, Integer> reasons, List<Link> links) {

        /**
         * <b>Собирает карту offline-отчёта.</b>
         *
         * <p>Сохраняет admitted=0 и network_requests=0.
         *
         * @return карта результата
         */
        Map<String, Object> summary() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", source);
            r.put("base_url", base_url);
            r.put("links_found", links_found);
            r.put("processed", processed);
            r.put("truncated", truncated);
            r.put("unique", unique);
            r.put("duplicates", duplicates);
            r.put("in_scope", in_scope);
            r.put("out_of_scope", out_of_scope);
            r.put("rejected", rejected);
            r.put("reasons", reasons);
            r.put("admitted", 0);
            r.put("network_requests", 0);
            r.put("frontier_admission", "UNAVAILABLE");
            r.put("links", links);
            return r;
        }
    }

    /**
     * <b>Извлекает offline-отчёт о bounded ссылках.</b>
     *
     * <p>Разрешает ссылки относительно явного source, применяет TorPolicy и metadata-only правило без admission.
     *
     * @param file локальный HTML-файл
     * @param source источник обнаружения
     * @param config конфигурация transport или scope
     * @return отчёт обнаружения
     * @throws IOException при ошибке операции или нарушении контракта
     */
    static Report discover(Path file, String source, Map<String, Object> config) throws IOException {
        TorPolicy scope = new TorPolicy(config);
        String base = normalize(scope, canonical(source, config));
        if (!Files.isRegularFile(file)) {
            throw new IOException("Require a regular saved HTML file");
        }
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) HtmlFiles.INSPECT_LIMIT + 1);
        }
        if (bytes.length > HtmlFiles.INSPECT_LIMIT) {
            throw new IOException("Discovery limited to 4 MiB");
        }
        // Use the existing HTML/charset parser. Resolve against the explicit trusted base,
        // never a document-controlled <base> or JS/refresh directive.
        var anchors = HtmlFiles.parse(bytes, base).select("a[href]");
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int unique = 0, duplicates = 0, inside = 0, outside = 0, rejected = 0;
        for (var a : anchors) {
            if (links.size() == MAX_LINKS) {
                break;
            }
            String raw = a.attr("href"), target = null, reason;
            boolean valid = false, onionValid = false, inScope = false, eligible = false;
            try {
                if (raw.length() > MAX_URL) {
                    throw new IOException("URL_LIMIT");
                }
                URI ref = new URI(raw), parent = URI.create(base);
                valid = true;
                // URI.resolve("?q") drops the last path segment on the JDK; query-only
                // references must keep the source path. Empty/fragment references are self.
                String resolved;
                if (raw.isEmpty() || raw.startsWith("#")) {
                    resolved = base;
                } else if (raw.startsWith("?")) {
                    resolved = base.split("\\?", 2)[0] + raw;
                } else {
                    resolved = parent.resolve(ref).toString();
                }
                String host = URI.create(resolved).getHost();
                onionValid = host != null && TorPolicy.validOnion(host.toLowerCase(Locale.ROOT));
                target = canonical(resolved, config);
                try {
                    normalize(scope, target);
                    inScope = true;
                } catch (IOException excluded) {
                }
                if (!seen.add(target)) {
                    reason = "DUPLICATE";
                    duplicates++;
                } else {
                    unique++;
                    if (inScope) {
                        inside++;
                        eligible = !DiscoveryPlanner.metadataOnly(target);
                        reason = eligible ? "IN_SCOPE" : "METADATA_ONLY";
                    } else {
                        outside++;
                        reason = "OUT_OF_SCOPE";
                    }
                }
            } catch (java.net.URISyntaxException e) {
                reason = "INVALID_URI";
                rejected++;
            } catch (IOException e) {
                reason = e.getMessage();
                rejected++;
            } catch (IllegalArgumentException e) {
                reason = "INVALID_URI";
                rejected++;
            }
            reasons.merge(reason, 1, Integer::sum);
            links.add(new Link(base, raw.length() > MAX_URL ? "[overlong reference]" : raw.contains("@") ?
                "[redacted reference]" : raw, target, valid, onionValid, inScope, eligible, false, reason));
        }
        return new Report(file.toAbsolutePath().toString(), base, anchors.size(), links.size(),
            anchors.size() > links.size(), unique, duplicates, inside, outside, rejected,
            Collections.unmodifiableMap(reasons), List.copyOf(links));
    }

    /**
     * <b>Делегирует нормализацию TorPolicy.</b>
     *
     * <p>Объявляет IOException для Java-кода, поскольку Kotlin не объявляет checked throws.
     *
     * @param policy scope policy
     * @param url URL страницы
     * @return нормализованный URL
     * @throws IOException при ошибке операции или нарушении контракта
     */
    private static String normalize(TorPolicy policy, String url) throws IOException {
        // Kotlin exposes IOException at runtime without a Java throws declaration.
        return policy.normalize(url);
    }

    /**
     * <b>Проверяет структуру onion URL для отчёта.</b>
     *
     * <p>Временная scope policy валидирует только грамматику; исходная policy определяет in_scope.
     *
     * @param value проверяемое значение
     * @param config конфигурация transport или scope
     * @return канонический URL
     * @throws IOException при ошибке операции или нарушении контракта
     */
    private static String canonical(String value, Map<String, Object> config) throws IOException {
        if (value.length() > MAX_URL) {
            throw new IOException("URL_LIMIT");
        }
        URI u;
        try {
            u = new URI(value);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("INVALID_URI");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("UNSUPPORTED_SCHEME");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!TorPolicy.validOnion(host)) {
            throw new IOException("INVALID_ONION");
        }
        if (u.getRawUserInfo() != null || u.getRawAuthority() == null || !u.getRawAuthority()
            .equalsIgnoreCase(u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort()))) {
            throw new IOException("INVALID_AUTHORITY");
        }
        int port = u.getPort();
        if (port != -1 && port != (scheme.equals("http") ? 80 : 443)) {
            throw new IOException("INVALID_PORT");
        }
        String candidate = scheme + "://" + host + (u.getRawPath().isEmpty() ? "/" : u.getRawPath()) +
            (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        // Structural validation for reporting an unknown onion uses the SAME policy grammar.
        // This temporary policy cannot admit/fetch: only the original scope decides in_scope.
        Map<String, Object> validation = new HashMap<>(config);
        validation.put("tor.allowed.hosts", List.of(host));
        try {
            return normalize(new TorPolicy(validation), candidate);
        } catch (IOException e) {
            throw new IOException("UNSAFE_URL");
        }
    }
}
