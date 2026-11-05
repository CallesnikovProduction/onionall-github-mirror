package dev.onionall.brawler.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.parse.ParseFilter;
import org.apache.stormcrawler.parse.ParseResult;
import org.w3c.dom.DocumentFragment;

/**
 * <b>Сохранение успешного HTML перед эмиссией parser.</b>
 *
 * <p>Проверяет Tor-конфигурацию и approval. Ошибка commit попадает в существующий ERROR-путь JSoupParserBolt; replay использует идемпотентную запись каталога.
 */
public final class ArchiveParseFilter extends ParseFilter {

    private Path root;

    private String job;

    private String prefix;

    private TorPolicy policy;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * <b>Проверяет архив, approval и transport конфигурацию.</b>
     *
     * <p>HTTP и HTTPS должны использовать TorProtocol с явным metadata prefix.
     *
     * @param conf конфигурация компонента
     * @param params параметры ParseFilter
     * @param name имя фильтра
     */
    @Override
    public void configure(Map<String, Object> conf, JsonNode params, String name) {
        super.configure(conf, params, name);
        root = requiredRoot(conf);
        job = requiredJob(conf);
        prefix = Objects.toString(conf.get("protocol.md.prefix"), "");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("Archive requires protocol.md.prefix");
        }
        if (!"dev.onionall.brawler.tor.TorProtocol".equals(conf.get("http.protocol.implementation")) ||
            !"dev.onionall.brawler.tor.TorProtocol".equals(conf.get("https.protocol.implementation"))) {
            throw new IllegalArgumentException("Archive pipeline requires TorProtocol for HTTP and HTTPS");
        }
        policy = new TorPolicy(conf);
        try (var store = new ArchiveStore(root)) {
            if (!Boolean.TRUE.equals(store.job(job).get("approved"))) {
                throw new IOException("Archive job requires approval");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Archive job unavailable", e);
        }
    }

    /**
     * <b>Извлекает путь архива из конфигурации.</b>
     *
     * <p>Пустое значение запрещает запуск компонента.
     *
     * @param conf конфигурация компонента
     * @return путь архива
     */
    static Path requiredRoot(Map<String, Object> conf) {
        Object value = conf.get("archive.root");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Require archive.root");
        }
        return Path.of(text);
    }

    /**
     * <b>Проверяет идентификатор архивного задания.</b>
     *
     * <p>Использует существующий формат archive.job.id.
     *
     * @param conf конфигурация компонента
     * @return идентификатор задания
     */
    static String requiredJob(Map<String, Object> conf) {
        Object value = conf.get("archive.job.id");
        if (!(value instanceof String text) || !text.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Require archive.job.id");
        }
        return text;
    }

    /**
     * <b>Сохраняет bounded успешный HTML в каталог.</b>
     *
     * <p>После commit записывает archive.page.id и archive.committed в metadata; ошибка сохраняет штатный ERROR-путь parser.
     *
     * @param url URL страницы
     * @param content байты HTML
     * @param fragment DOM-фрагмент
     * @param parse результат разбора
     */
    @Override
    public void filter(String url, byte[] content, DocumentFragment fragment, ParseResult parse) {
        if (root == null) {
            throw new IllegalStateException("Archive filter not configured");
        }
        try {
            String canonical = policy.normalize(url);
            Metadata md = Objects.requireNonNull(parse.get(url), "Missing parent").getMetadata();
            int status = Integer.parseInt(Objects.requireNonNull(md.getFirstValue("fetch.statusCode"),
                "Missing HTTP status"));
            String type = md.getFirstValue(prefix + "content-type");
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("text/html") || status < 200 ||
                status >= 300) {
                throw new IOException("Archive requires successful HTML response");
            }
            int depth = Integer.parseInt(Objects.requireNonNullElse(md.getFirstValue("discovery.depth"), "0"));
            String source = Objects.requireNonNullElse(md.getFirstValue("discovery.source"), canonical);
            List<Map<String, Object>> links = new ArrayList<>();
            String[] edges = md.getValues("discovery.edges");
            if (edges != null) {
                if (edges.length > 100) {
                    throw new IOException("Archive link bound exceeded");
                }
                for (String edge : edges) {
                    if (edge.length() > 8192) {
                        throw new IOException("Archive edge bound exceeded");
                    }
                    links.add(json.readValue(edge, new TypeReference<Map<String, Object>>() {
                    }));
                }
            }
            try (var store = new ArchiveStore(root)) {
                Map<String, Object> archived = store.archive(job, canonical, depth, source, status, type, content,
                    links);
                md.setValue("archive.page.id", Objects.toString(archived.get("page_id")));
                md.setValue("archive.committed", "true");
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Archive commit failed; fetched success must not be acknowledged", e);
        }
    }
}
