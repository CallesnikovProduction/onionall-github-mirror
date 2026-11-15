package dev.onionall.brawler.archive.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * <b>Общие проверки контрактов архива.</b>
 *
 * <p>Не владеет JDBC или файлами; операции не меняют persistence.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class ArchiveSupport {

    /** Specification keys, которые observation не может переопределять в job projection. */
    static final Set<String> PLAN_AUTHORITY_FIELDS = Set.of(
            "plan_id", "job_id", "seeds", "origin", "scope",
            "depth", "max_pages", "provider", "approved", "created_at"
    );

    /** Общий mapper после создания не перенастраивается; materialized maps принадлежат callers. */
    static final ObjectMapper ARCHIVE_STORE_JSON_SERIALIZER = new ObjectMapper();

    private ArchiveSupport() {}

    /**
     * <b>Проверяет путь и родителей на symlink.</b>
     *
     * <p>Проверка не удаляет файлы и не исправляет структуру архива.
     *
     * @param path проверяемый путь
     * @throws IOException при ошибке операции или нарушении контракта
     */
    static void noSymlinks(Path path) throws IOException {
        for (
                Path current = path;
                current != null;
                current = current.getParent()
        ) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Archive paths must not contain symbolic links");
            }
        }
    }

    /**
     * Оборачивает ошибку без включения SQL/HTML в собственное diagnostic message.
     * @param e исходная ошибка; сохраняется как cause
     * @return IOException, не меняющая persistence outcome
     */
    static IOException failure(Exception e) {
        return new IOException("Archive operation failed: " + e.getClass().getSimpleName(), e);
    }

    /**
     * Проверяет существующий UUID-контракт идентификаторов.
     *
     * @param id UUID lowercase
     * @throws IOException при неверном формате
     */
    static void identifier(String id) throws IOException {
        if (
                id == null ||
                !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        ) {
            throw new IOException("Invalid archive identifier");
        }
    }

    /**
     * Проверяет границу bounded serializableRead API.
     *
     * @param value запрошенный limit
     * @throws IOException при выходе за диапазон
     */
    static void bound(int value) throws IOException {
        if (value < 1 || value > 100) {
            throw new IOException("Require result limit1..100");
        }
    }

    /**
     * Проверяет non-null строку, длину и отсутствие NUL.
     * @param value строка
     * @param max максимальная длина UTF-16
     * @param field имя поля для диагностики без содержимого
     * @return проверенная строка
     * @throws IOException при нарушении bound
     */
    static String bounded(
            String value,
            int max,
            String field
    ) throws IOException {
        if (
                value == null ||
                value.length() > max ||
                value.chars().anyMatch(c -> c == 0)
        ) {
            throw new IOException("Invalid " + field);
        }
        return value;
    }

    /**
     * Создаёт локальный URL validator в ONLY mode без сетевого обращения.
     * @param host разрешённый onion v3 hostname
     * @return scope policy
     * @throws IOException при неверном onion hostname
     */
    static TorPolicy policy(String host) throws IOException {
        if (!TorPolicy.validOnion(host)) {
            throw new IOException("Require valid onion v3 hostname");
        }
        return new TorPolicy(
                Map.of(
                        "tor.mode", "ONLY",
                        "tor.socks.host", "127.0.0.1",
                        "tor.socks.port", 9050,
                        "tor.allowed.hosts", List.of(host)
                )
        );
    }

    /**
     * <b>Нормализует onion URL в границах origin.</b>
     *
     * <p>Использует TorPolicy и ограничение длины; не выполняет сетевой запрос.
     *
     * @param url URL страницы
     * @param origin разрешённый origin либо null
     * @return нормализованный URL
     * @throws IOException при ошибке операции или нарушении контракта
     */
    static String normalize(
            String url,
            String origin
    ) throws IOException {
        bounded(url, 2048, "URL");
        String host;
        try {
            host = URI.create(origin == null ? url : origin).getHost();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL", e);
        }
        String result = policy(host == null ? "" : host).normalize(url);
        if (origin != null && !origin.equals(origin(result))) {
            throw new IOException("Origin is outside approved same-host scope");
        }
        URI canonical = URI.create(result);
        if (canonical.getPort() != -1) {
            result = origin(result) +
                    canonical.getRawPath() +
                    (
                            canonical.getRawQuery() == null
                                    ? ""
                                    : "?" + canonical.getRawQuery()
                    );
        }
        return result;
    }

    /**
     * Выделяет scheme/host из уже проверенного canonical URL.
     * @param url нормализованный URL
     * @return same-origin boundary без path/query
     */
    static String origin(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getHost();
    }

    /**
     * Читает scalar count в существующем ownership context без commit.
     * @param db заимствованное соединение под monitor
     * @param sql доверенный SQL с одним placeholder
     * @param id значение placeholder
     * @return число строк
     * @throws SQLException при ошибке JDBC
     */
    static long count(Connection db, String sql, String id) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            query.setString(1, id);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }


    /**
     * Считает distinct URLs HTML и text под guard; один URL не расходует бюджет дважды.
     * @param db borrowed connection
     * @param jobId план
     * @return число архивных URLs
     * @throws SQLException при ошибке JDBC
     */
    static long archivedUrlCount(Connection db, String jobId) throws SQLException {
        try (var query = db.prepareStatement("SELECT COUNT(*) FROM (SELECT canonical_url FROM pages WHERE job_id=? UNION SELECT canonical_url FROM archive_document_pages_v1 WHERE job_id=?)")) {
            query.setString(1, jobId);
            query.setString(2, jobId);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
}
