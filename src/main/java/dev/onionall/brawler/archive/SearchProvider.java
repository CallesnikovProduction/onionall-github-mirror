package dev.onionall.brawler.archive;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * <b>Контракт локального и assisted-поиска.</b>
 *
 * <p>Реализации явно различают чтение каталога и подготовку ручного действия; assisted-поиск не скачивает результаты.
 */
public interface SearchProvider {

    /**
     * <b>Выполняет поиск согласно возможностям реализации.</b>
     *
     * <p>LocalArchive читает H2; AssistedAhmia возвращает ссылку для ручного открытия.
     *
     * @param query поисковая строка
     * @param limit максимальный размер результата
     * @return карта результатов либо требуемого действия
     * @throws IOException при ошибке операции или нарушении контракта
     */
    Map<String, Object> search(String query, int limit) throws IOException;

    /**
     * <b>Поиск в существующем локальном архиве.</b>
     *
     * <p>На каждый запрос открывает и закрывает ArchiveStore.
     */
    final class LocalArchive implements SearchProvider {

        private final Path root;

        private final String job;

        public LocalArchive(Path root, String job) {
            this.root = root;
            this.job = job;
        }

        /**
         * <b>Выполняет поиск согласно возможностям реализации.</b>
         *
         * <p>LocalArchive читает H2; AssistedAhmia возвращает ссылку для ручного открытия.
         *
         * @param query поисковая строка
         * @param limit максимальный размер результата
         * @return карта результатов либо требуемого действия
         * @throws IOException при ошибке операции или нарушении контракта
         */
        public Map<String, Object> search(String query, int limit) throws IOException {
            try (var store = new ArchiveStore(root)) {
                return store.search(query, job, limit);
            }
        }
    }

    /**
     * <b>Подготовка официальной ссылки Ahmia для пользователя.</b>
     *
     * <p>Возвращает USER_ACTION_REQUIRED без HTTP-запроса или scraping.
     */
    final class AssistedAhmia implements SearchProvider {

        /**
         * <b>Выполняет поиск согласно возможностям реализации.</b>
         *
         * <p>LocalArchive читает H2; AssistedAhmia возвращает ссылку для ручного открытия.
         *
         * @param query поисковая строка
         * @param limit максимальный размер результата
         * @return карта результатов либо требуемого действия
         * @throws IOException при ошибке операции или нарушении контракта
         */
        public Map<String, Object> search(String query, int limit) throws IOException {
            if (query == null || query.isBlank() || query.length() > 256 || limit < 1 || limit > 100) {
                throw new IOException("Require nonblank query up to256 characters and limit1..100");
            }
            return Map.of("provider", "ahmia", "status", "USER_ACTION_REQUIRED", "network_requested", false,
                "programmatic_search", false, "official_url", "https://ahmia.fi/search/?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8), "instruction",
                "Open the official page yourself and import manually selected permitted URLs with --provider ahmia; no results have been downloaded");
        }
    }
}
