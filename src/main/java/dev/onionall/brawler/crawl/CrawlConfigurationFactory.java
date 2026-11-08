package dev.onionall.brawler.crawl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.onionall.brawler.crawl.RuntimeDescriptor.*;

/**
 * <b>Построение конфигурации ограниченного Tor-обхода.</b>
 *
 * <p>Не запускает процессы и не обращается к Frontier.
 */
public final class CrawlConfigurationFactory {

    private CrawlConfigurationFactory() {
    }

    /**
     * <b>Формирует параметры worker из плана и runtime-карты.</b>
     *
     * <p>Сохраняет ограничения транспорта, admission и архива.
     *
     * @param plan утверждённый план
     * @param runtime параметры среды выполнения
     * @param archive каталог архива
     * @param tokenFile файл capability
     * @param work рабочий каталог задания
     * @param timeout таймаут HTTP в миллисекундах
     * @param retry интервал повтора в минутах
     * @return конфигурация StormCrawler
     * @throws IOException если runtime-параметры некорректны
     */
    public static Map<String, Object> build(
        Map<String, Object> plan,
        Map<String, Object> runtime,
        Path archive,
        Path tokenFile,
        Path work,
        int timeout,
        int retry
    ) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("topology.workers", 1);
        c.put("topology.message.timeout.secs", 300);
        c.put("topology.max.spout.pending", 10);
        c.put("topology.kryo.register", List.of("org.apache.stormcrawler.Metadata",
            "org.apache.stormcrawler.persistence.Status"));
        c.put("storm.log.dir", work.resolve("logs").toString());
        c.put("storm.local.dir", work.resolve("storm-local").toString());
        c.put("dev.zookeeper.path", work.resolve("zookeeper").toString());
        c.put("urlfrontier.host", text(runtime, "frontier_host"));
        c.put("urlfrontier.port", number(runtime, "frontier_port", 7071, 65535));
        c.put("urlfrontier.crawlid", plan.get("plan_id"));
        c.put("urlfrontier.max.buckets", 1);
        c.put("urlfrontier.max.urls.per.bucket", 2);
        c.put("urlfrontier.max.messages.in.flight", 10);
        c.put("urlfrontier.delay.requestable", 300);
        c.put("fetcher.threads.number", 1);
        c.put("fetcher.threads.per.queue", 1);
        c.put("fetcher.server.delay", 2.0);
        c.put("fetcher.server.delay.force", true);
        c.put("fetcher.max.throttle.sleep", -1);
        c.put("http.protocol.implementation", "dev.onionall.brawler.tor.TorProtocol");
        c.put("https.protocol.implementation", "dev.onionall.brawler.tor.TorProtocol");
        c.put("http.timeout", timeout);
        c.put("http.content.limit", 131072);
        c.put("http.agent.name", "OnionallBrawler");
        c.put("http.robots.file.skip", false);
        c.put("http.allow.redirects", false);
        c.put("http.trust.everything", false);
        c.put("tor.mode", "ONLY");
        c.put("tor.socks.host", text(runtime, "socks_host"));
        c.put("tor.socks.port", number(runtime, "socks_port", 9050, 65535));
        c.put("tor.allowed.hosts", List.of(java.net.URI.create((String) plan.get("origin")).getHost()));
        c.put("tor.discovery.html.only", true);
        c.put("protocol.md.prefix", "protocol.");
        c.put("metadata.persist", List.of("discovery.*", "fetch.exception", "fetch.statusCode",
            "protocol.tor.category", "error.cause", "error.source"));
        c.put("metadata.transfer", List.of());
        c.put("parser.emitOutlinks.max.per.page", 100);
        c.put("discovery.admission.timeout.ms", Math.min(30000, number(runtime, "seconds", 120, 600) * 1000));
        c.put("sitemap.discovery", false);
        c.put("fetchInterval.default", 1440);
        c.put("fetchInterval.fetch.error", retry);
        c.put("fetchInterval.error", -1);
        // JSoupParser emits ERROR then ACK for filter exceptions. Only the discovery
        // failure marker opts into a future retry; ordinary ERROR remains terminal.
        c.put("fetchInterval.ERROR.onionall.discovery.retry=true", retry);
        c.put("max.fetch.errors", 3);
        // Keep the canonical indexer/status stage while preventing document payloads
        // and URLs from being copied into retained worker stdout by crawler defaults.
        c.put("indexer.url.fieldname", "");
        c.put("indexer.text.fieldname", "");
        c.put("indexer.md.mapping", List.of());
        c.put("parsefilters.config.file", "onionall-job/parsefilters.json");
        c.put("urlfilters.config.file", "onionall-job/urlfilters.json");
        c.put("archive.root", archive.toString());
        c.put("archive.job.id", plan.get("plan_id"));
        c.put("discovery.budget.mode", "durable-v1");
        c.put("admission.host", text(runtime, "frontier_host"));
        c.put("admission.port", number(runtime, "frontier_port", 7071, 65535));
        c.put("admission.token.file", tokenFile.toString());
        c.put("admission.session.id", plan.get("plan_id"));
        return c;
    }
}
