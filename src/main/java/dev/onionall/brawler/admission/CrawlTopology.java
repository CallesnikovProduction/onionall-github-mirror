package dev.onionall.brawler.admission;

import java.nio.file.Path;
import java.util.*;

/**
 * <b>Построение канонического графа Storm.</b>
 *
 * <p>Не запускает worker и не выполняет сетевых, каталоговых или scheduler-операций.
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class CrawlTopology {

    private CrawlTopology() {
    }

    /**
     * <b>Строит каноническую Flux-топологию.</b>
     *
     * <p>Сохраняет capability-aware spout/status updater и порядок streams.
     *
     * @param config путь конфигурации
     * @return карта топологии без побочных эффектов
     */
    static Map<String, Object> build(Path config) {
        var bolts = new ArrayList<Map<String, Object>>();

        // TODO(TOPO-001): Квалифицировать delayed/duplicate/out-of-order status events.
        // FIELDS(url) задаёт routing, UUID status request связывает только send/ACK,
        // но не доказывает fencing crawl attempts. Нужен общий контракт producers,
        // CapabilityArchiveStatusUpdaterBolt и Frontier; topology не владеет переходами.
        for (String[] b : List.of(
                new String[] {
                        "partitioner",
                        "org.apache.stormcrawler.bolt.URLPartitionerBolt"
                },
                new String[] {
                        "fetcher",
                        "org.apache.stormcrawler.bolt.SimpleFetcherBolt"
                },
                new String[] {
                        "parse",
                        "org.apache.stormcrawler.bolt.JSoupParserBolt"
                },

                // Product config disables document/URL/metadata stdout. Keep this
                // component for its existing FETCHED/status and ACK semantics.
                new String[] {
                        "index",
                        "org.apache.stormcrawler.indexing.StdOutIndexer"
                },
                new String[] {
                        "status",
                        "dev.onionall.brawler.archive.CapabilityArchiveStatusUpdaterBolt"
                })
        ) {
            bolts.add(Map.of(
                    "id", b[0],
                    "className", b[1],
                    "parallelism", 1)
            );
        }

        var streams = new ArrayList<Map<String, Object>>();

        // TODO(TOPO-002): Проверить весь ACK/lease/retry путь при worker crash и
        // downstream failure. JSoupParser 3.7 переводит parse-filter exception в
        // anchored ERROR + ACK; discovery retry marker задаёт лишь scheduling policy.
        // Сохранность reserved work требует интеграционного доказательства Frontier/
        // status updater/Storm, а не нового state machine внутри graph builder.
        streams.add(Map.of(
                "from", "spout",
                "to", "partitioner",
                "grouping", Map.of(
                        "type", "SHUFFLE"
                ))
        );


        // TODO(TOPO-003): Квалифицировать fetch policy в generated product graph.
        // Generated config выбирает TorProtocol ONLY и no redirects; component tests
        // проверяют SOCKS/scope/no-DIRECT controls, но не заменяют полный deployment.
        // Требуется согласованная проверка fetcher, TorProtocol и сетевой изоляции.
        streams.add(Map.of(
                "from", "partitioner",
                "to", "fetcher",
                "grouping", Map.of(
                        "type", "FIELDS",
                        "args", List.of("key"))
        ));
        streams.add(Map.of(
                "from", "fetcher",
                "to", "parse",
                "grouping", Map.of("type", "LOCAL_OR_SHUFFLE")
        ));
        streams.add(Map.of(
                "from", "parse",
                "to", "index",
                "grouping", Map.of("type", "LOCAL_OR_SHUFFLE")
        ));

        for (String from : List.of("fetcher", "parse", "index")) {
            streams.add(Map.of(
                    "from", from,
                    "to", "status",
                    "grouping", Map.of(
                            "type", "FIELDS",
                            "args", List.of("url"),
                            "streamId", "status")
            ));
        }


        // TODO(TOPO-005): Квалифицировать cluster mode или parallelism > 1 отдельно.
        // Текущий launcher использует local mode, graph name фиксирован, parallelism=1;
        // archive-wide ownership запрещает второй local worker того же архива.
        // Для расширения нужны проверки topology identity, status ordering и lease recovery.
        return Map.of(
                "name", "onionall-bounded",
                "includes", List.of(
                        Map.of(
                                "resource", true,
                                "file", "/crawler-default.yaml",
                                "override", false
                        ),
                        Map.of(
                                "resource", false,
                                "file", config.toString(),
                                "override", true
                        )
                ),
                "spouts", List.of(
                        Map.of(
                                "id", "spout",
                                "className", "org.apache.stormcrawler.urlfrontier.CapabilitySpout",
                                "parallelism", 1
                        )
                ),
                "bolts", bolts, "streams",
                streams
        );
    }
}
