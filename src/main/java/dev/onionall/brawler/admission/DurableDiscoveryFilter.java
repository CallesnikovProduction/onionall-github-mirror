package dev.onionall.brawler.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseFilter;
import org.apache.stormcrawler.parse.ParseResult;
import org.jsoup.Jsoup;
import org.w3c.dom.DocumentFragment;

/**
 * <b>Допуск найденных ссылок через Frontier.</b>
 *
 * <p>Сервер владеет квотой, глубиной и расписанием; локального admission-кэша нет. Граф bounded HTML сохраняет и отклонённые связи, не разрешая их загрузку.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class DurableDiscoveryFilter extends ParseFilter {

    private String host, session;

    private Path tokenFile;

    private int port;

    private int pageBudgetMillis;

    private final LongSupplier nanoTime;

    public DurableDiscoveryFilter() {
        this(System::nanoTime);
    }

    DurableDiscoveryFilter(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    private dev.onionall.brawler.tor.TorPolicy policy;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * <b>Проверяет конфигурацию durable discovery.</b>
     *
     * <p>Требует согласованную сессию архива/Frontier и capability; не допускает локальный режим квоты.
     *
     * @param conf конфигурация компонента
     * @param params параметры фильтра
     * @param name имя фильтра
     */
    @Override
    public void configure(
            Map<String, Object> conf,
            JsonNode params,
            String name
    ) {
        super.configure(conf, params, name);
        if (!"durable-v1".equals(conf.get("discovery.budget.mode"))) {
            throw new IllegalArgumentException("Require durable-v1 admission");
        }
        policy = new dev.onionall.brawler.tor.TorPolicy(conf);
        try {
            pageBudgetMillis = dev.onionall.brawler.crawl.RuntimeDescriptor.number(
                conf, "discovery.admission.timeout.ms", 30000, 30000);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid discovery admission budget");
        }
        host = required(conf, "admission.host");
        port = Integer.parseInt(required(conf, "admission.port"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid admission port");
        }
        session = required(conf, "admission.session.id");
        if (
                !session.equals(conf.get("archive.job.id")) ||
                        !session.equals(conf.get("urlfrontier.crawlid"))
        ) {
            throw new IllegalArgumentException("Archive, Frontier and admission session must agree");
        }
        try {
            tokenFile = Path.of(required(conf, "admission.token.file"));
            FrontierCapability.token(tokenFile);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Admission capability unavailable");
        }
    }

    /**
     * <b>Извлекает обязательный параметр фильтра.</b>
     *
     * <p>Отсутствующее или пустое значение запрещает конфигурацию.
     *
     * @param conf конфигурация компонента
     * @param key имя поля
     * @return непустое строковое значение
     */
    private static String required(
            Map<String, Object> conf,
            String key
    ) {
        Object v = conf.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return v.toString();
    }

    /**
     * <b>Нормализует URL общей TorPolicy.</b>
     *
     * <p>Применяет существующие ограничения scope без сетевого обращения.
     *
     * @param url проверяемый URL
     * @return нормализованный допустимый URL
     * @throws IOException при ошибке операции или нарушении её контракта
     */
    private String canonicalTarget(String url) throws IOException {
        String normalized = policy.normalize(url);
        URI parsed = URI.create(normalized);
        // Frontier canonical identity omits the scheme's default port.
        return parsed.getPort() == -1 ? normalized : parsed.getScheme() + "://" + parsed.getHost() +
            parsed.getRawPath() + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
    }

    @Override
    public boolean needsDOM() {
        return false;
    }

    /**
     * <b>Резервирует допустимые outlinks перед эмиссией.</b>
     *
     * <p>Формирует bounded graph metadata для последующего ArchiveParseFilter;
     * квотой и решением admission управляет Frontier. Неуспешный callback оставляет
     * explicit incomplete/retry intent; parser ACK сам по себе не доказывает archive commit.
     *
     * @param url проверяемый URL
     * @param content исходное bounded содержимое HTML
     * @param fragment DOM-фрагмент парсера
     * @param result результат разбора и список outlinks
     */
    @Override
    public void filter(String url, byte[] content, DocumentFragment fragment, ParseResult result) {
        if (host == null) {
            throw new IllegalStateException("Admission filter not configured");
        }
        if (content.length > 131072) {
            throw new IllegalArgumentException("HTML exceeds admission inspection limit");
        }
        Metadata md = Objects.requireNonNull(result.get(url), "Missing parent").getMetadata();
        md.remove("onionall.discovery.retry");
        md.remove("discovery.error");
        md.remove("discovery.edges");
        md.remove("discovery.links.truncated");
        md.setValue("discovery.processing.complete", "false");
        long deadline = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(pageBudgetMillis);
        try (AdmissionClient client = AdmissionClient.fromCapabilityFile(host, port, tokenFile)) {
            if (!url.equals(canonicalTarget(url))) {
                throw new IOException("Noncanonical discovery parent");
            }
            var parent = verified(request(client,
                Map.of("action", "reservation", "session_id", session, "url", url), deadline), url);
            if (!"RESERVED".equals(parent.outcome())) {
                throw new IOException("Source has no authoritative reservation");
            }
            md.setValue("discovery.depth", Integer.toString(parent.depth()));
            md.setValue("discovery.source", parent.sourceUrl().isEmpty() ? url : parent.sourceUrl());
            var document = Jsoup.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8), url);
            document.select("base").remove();
            List<String> edges = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            URI origin = URI.create(url);
            int examined = 0;
            for (var anchor : document.select("a[href]")) {
                remaining(deadline);
                if (++examined > 100) {
                    break;
                }
                String raw = anchor.attr("href");
                if (raw.length() > 2048) {
                    continue;
                }
                URI resolved;
                try {
                    resolved = origin.resolve(URI.create(raw));
                } catch (IllegalArgumentException invalidCandidate) {
                    edges.add(edge(url, "", "INVALID_URL"));
                    continue;
                }
                if (!Set.of("http", "https").contains(Objects.toString(resolved.getScheme(), "")) ||
                    resolved.getHost() == null || resolved.getRawUserInfo() != null) {
                    continue;
                }
                String target = resolved.toASCIIString().split("#", 2)[0];
                if (target.length() > 2048 || !seen.add(target)) {
                    continue;
                }
                String disposition;
                if (!Objects.equals(resolved.getHost(), origin.getHost()) ||
                    !Objects.equals(resolved.getScheme(), origin.getScheme())) {
                    disposition = "SCOPE_REJECTED";
                } else {
                    String canonical = null;
                    try {
                        canonical = canonicalTarget(target);
                    } catch (IOException rejectedCandidate) {
                        // Only URL-policy rejection belongs to this candidate boundary.
                    }
                    if (canonical == null) {
                        disposition = "INVALID_URL";
                    } else {
                        if (!target.equals(canonical) && !seen.add(canonical)) continue;
                        target = canonical;
                        disposition = reserve(client, url, parent.depth() + 1, target, deadline);
                    }
                }
                edges.add(edge(url, target, disposition));
            }
            remaining(deadline);
            md.setValues("discovery.edges", edges.toArray(String[]::new));
            md.setValue("discovery.links.truncated", Boolean.toString(examined > 100));
            md.setValue("discovery.processing.complete", "true");
            // Frontier already enqueued accepted URLs; parser legacy discovery stays disabled.
            result.setOutlinks(Collections.emptyList());
        } catch (IOException | RuntimeException failure) {
            // Installed parser emits ERROR then ACK. The generated scheduler selectively
            // retries this trusted marker; partial reservations remain authoritative.
            md.setValue("onionall.discovery.retry", "true");
            md.setValue("discovery.processing.complete", "false");
            md.setValue("discovery.error", failure instanceof BudgetExceeded
                ? "TIME_BUDGET_EXHAUSTED" : "ADMISSION_UNRESOLVED");
            if (failure instanceof BudgetExceeded) md.setValue("discovery.links.truncated", "true");
            throw new IllegalStateException("Durable discovery incomplete; admitted work remains in Frontier", failure);
        }
    }

    private String edge(String source, String target, String disposition) throws IOException {
        return json.writeValueAsString(Map.of("source_url", source, "target_url", target,
            "disposition", disposition, "discovered_at", Instant.now().toString(), "job_id", session));
    }

    private FrontierReadback.Reservation verified(Map<String, Object> reply, String url) throws IOException {
        var reservation = FrontierReadback.reservation(reply, session, url);
        if (!url.equals(canonicalTarget(url)) || (!reservation.sourceUrl().isEmpty() &&
            (!reservation.sourceUrl().equals(canonicalTarget(reservation.sourceUrl())) ||
             !origin(url).equals(origin(reservation.sourceUrl()))))) {
            throw new IOException("Invalid authoritative reservation provenance");
        }
        return reservation;
    }

    private static String origin(String url) {
        URI parsed = URI.create(url);
        return parsed.getScheme() + "://" + parsed.getHost();
    }

    private String reserve(AdmissionClient client, String source, int depth, String url, long deadline)
        throws IOException {
        Map<String, Object> reply;
        try {
            reply = request(client, Map.of("action", "reserve", "session_id", session, "url", url,
                "source_url", source, "depth", depth), deadline);
        } catch (AdmissionClient.RpcFailure ambiguous) {
            if (ambiguous.kind() != AdmissionClient.FailureKind.TRANSPORT) throw ambiguous;
            // A positive immutable read resolves commit. Negative/unavailable never retries.
            try {
                var observed = verified(request(client,
                    Map.of("action", "reservation", "session_id", session, "url", url), deadline), url);
                if (!"RESERVED".equals(observed.outcome())) {
                    throw new IOException("Discovery reservation outcome UNKNOWN");
                }
                return observed.outcome();
            } catch (IOException unresolved) {
                IOException failure = new IOException("Discovery reservation outcome UNKNOWN", ambiguous);
                failure.addSuppressed(unresolved);
                if (unresolved instanceof BudgetExceeded) {
                    unresolved.addSuppressed(ambiguous);
                    throw (BudgetExceeded) unresolved;
                }
                throw failure;
            }
        }
        String outcome = (String) reply.get("outcome");
        if (Set.of("SCOPE_REJECTED", "DEPTH_REJECTED", "LIMIT_REJECTED", "METADATA_ONLY").contains(outcome)) {
            return outcome; // Rejection envelope has no reservation; never replace target from it.
        }
        var offered = verified(reply, url);
        if ("RESERVED".equals(offered.outcome()) &&
            (!source.equals(offered.sourceUrl()) || depth != offered.depth())) {
            throw new IOException("New reservation differs from requested provenance");
        }
        var observed = verified(request(client,
            Map.of("action", "reservation", "session_id", session, "url", url), deadline), url);
        if (!"RESERVED".equals(observed.outcome()) ||
            !offered.reservationId().equals(observed.reservationId()) ||
            !offered.sourceUrl().equals(observed.sourceUrl()) || offered.depth() != observed.depth()) {
            throw new IOException("Committed discovery reservation readback mismatch");
        }
        // ALREADY_RESERVED retains the original parent/depth, even through another graph edge.
        return offered.outcome();
    }

    private Map<String, Object> request(AdmissionClient client, Map<String, Object> command, long deadline)
        throws IOException {
        return client.command(command, remaining(deadline));
    }

    private long remaining(long deadline) throws BudgetExceeded {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) throw new BudgetExceeded();
        return Math.min(10000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static final class BudgetExceeded extends IOException {
        private BudgetExceeded() {
            super("Discovery admission time budget exhausted; processing incomplete");
        }
    }
}
