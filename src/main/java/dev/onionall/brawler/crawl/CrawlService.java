package dev.onionall.brawler.crawl;

import dev.onionall.brawler.admission.CrawlExecution;
import dev.onionall.brawler.archive.ArchiveStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * <b>
 *     Сервис управления планами обхода и запуском исследований.
 * </b>
 *
 * <p>Предоставляет единый прикладной интерфейс для:
 * <ul>
 *     <li>создания,</li>
 *     <li>просмотра и подтверждения планов обхода,</li>
 *     <li>получения истории ошибок</li>
 *     <li>запуска crawler</li>
 *     <li>и просмотра состояния выполнения</li>
 * </ul>
 *
 * <p>Операции с планами и историей ошибок делегируются
 * {@link ArchiveStore}, а запуск и получение runtime-статуса —
 * {@link CrawlExecution}.
 *
 * <p>Сервис не выполняет загрузку страниц и не управляет
 * очередью URL. Единственным источником полномочий для
 * допуска URL и планирования обхода остаётся Frontier.
 *
 * <p>Если при запуске не указан runtime-каталог, сервис
 * проверяет существование задания и возвращает результат
 * {@code BLOCKED}, не вызывая механизм запуска crawler.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class CrawlService {

    public static final String ADMISSION_BLOCKER = "Configure a compatible durable Frontier server " +
        "and pass --runtime with --admission-token-file; " + "stock Frontier has no safe admission fallback";

    /**
     * <b>
     *     Абстракция операций запуска и наблюдения за выполнением обхода.
     * </b>
     *
     * <p>Позволяет подменять реализацию выполнения при тестировании,
     * не запуская реальные процессы crawler.
     */
    public interface Execution {

        /**
         * Получает состояние выполнения задания.
         *
         * @param archive путь к каталогу архива
         * @param id идентификатор задания
         * @param runtime путь к runtime-каталогу
         * @param token путь к файлу capability-токена
         * @return структурированное представление состояния
         * @throws Exception при ошибке получения состояния
         */
        Map<String, Object> status(Path archive, String id, Path runtime, Path token) throws Exception;

        /**
         * Запускает выполнение задания.
         *
         * @param archive путь к каталогу архива
         * @param id идентификатор задания
         * @param runtime путь к runtime-каталогу
         * @param token путь к файлу capability-токена
         * @return структурированный результат попытки запуска
         * @throws Exception при ошибке запуска
         */
        Map<String, Object> start(Path archive, String id, Path runtime, Path token) throws Exception;
    }

    private final Path archive;

    private final Execution execution;

    /**
     * Создаёт сервис с реализацией выполнения на основе {@code CrawlExecution}.
     *
     * @param archive путь к каталогу архива
     */
    public CrawlService(Path archive) {
        this(archive, new Execution() {

            public Map<String, Object> status(Path root, String id, Path runtime, Path token) throws Exception {
                return CrawlExecution.status(root, id, runtime, token);
            }

            public Map<String, Object> start(Path root, String id, Path runtime, Path token) throws Exception {
                return CrawlExecution.start(root, id, runtime, token);
            }
        });
    }

    /**
     * Создаёт сервис с заданной реализацией операций выполнения.
     *
     * @param archive путь к каталогу архива
     * @param execution реализация запуска и получения состояния
     * @throws NullPointerException если аргумент равен null
     */
    public CrawlService(Path archive, Execution execution) {
        this.archive = Objects.requireNonNull(archive);
        this.execution = Objects.requireNonNull(execution);
    }

    /**
     * <b>
     *     Создаёт и сохраняет новый план обхода.
     * </b>
     *
     * @param seeds начальные URL исследования
     * @param depth максимальная глубина обхода
     * @param maxPages максимальное количество страниц
     * @param provider источник начальных URL
     * @return данные созданного плана
     * @throws IOException при ошибке работы с архивом
     */
    public Map<String, Object> createPlan(
        List<String> seeds,
        int depth,
        int maxPages,
        String provider
    ) throws IOException {
        try (var catalog = new ArchiveStore(archive)) {
            return catalog.createPlan(seeds, depth, maxPages, provider);
        }
    }

    /**
     * <b>
     *     Возвращает страницу списка планов обхода.
     * </b>
     *
     * @param cursor курсор пагинации
     * @param limit максимальное количество результатов
     * @return список планов и данные пагинации
     * @throws IOException при ошибке работы с архивом
     */
    public Map<String, Object> plans(String cursor, int limit) throws IOException {
        try (var catalog = new ArchiveStore(archive)) {
            return catalog.plans(cursor, limit);
        }
    }

    /**
     * <b>
     *     Возвращает сведения о плане по идентификатору.
     * </b>
     *
     * @param id идентификатор плана
     * @return данные найденного плана
     * @throws IOException при ошибке работы с архивом
     */
    public Map<String, Object> plan(String id) throws IOException {
        try (var catalog = new ArchiveStore(archive)) {
            return catalog.plan(id);
        }
    }

    /**
     * <b>
     *     Подтверждает ранее созданный план обхода.
     * </b>
     * @param id идентификатор плана
     * @return результат подтверждения
     * @throws IOException при ошибке работы с архивом
     */
    public Map<String, Object> approve(String id) throws IOException {
        try (var catalog = new ArchiveStore(archive)) {
            return catalog.approvePlan(id);
        }
    }

    /**
     * <b>
     *     Возвращает историю ошибок задания с пагинацией.
     * </b>
     *
     * @param id идентификатор задания
     * @param cursor курсор пагинации
     * @param limit максимальное количество записей
     * @return записи об ошибках и данные пагинации
     * @throws IOException при ошибке работы с архивом
     */
    public Map<String, Object> errors(String id, String cursor, int limit) throws IOException {
        try (var catalog = new ArchiveStore(archive)) {
            return catalog.failureHistory(id, cursor, limit);
        }
    }

    /**
     * <b>
     *     Получает состояние выполнения задания через Execution.
     * </b>
     *
     * @param id идентификатор задания
     * @param runtime путь к runtime-каталогу
     * @param token путь к файлу capability-токена
     * @return сведения о состоянии задания
     * @throws Exception при ошибке получения состояния
     */
    public Map<String, Object> status(String id, Path runtime, Path token) throws Exception {
        return execution.status(archive, id, runtime, token);
    }

    /**
     * <B>
     *     Инициирует запуск задания через Execution.
     * </B>
     *
     * <p>Если runtime не указан, проверяет существование задания
     * и возвращает BLOCKED с кодом 7 без запуска crawler.
     *
     * @param id идентификатор задания
     * @param runtime путь к runtime-каталогу или null
     * @param token путь к файлу capability-токена
     * @return результат запуска либо отказа
     * @throws Exception при ошибке проверки задания или запуска
     */
    public Map<String, Object> start(String id, Path runtime, Path token) throws Exception {
        if (runtime != null) {
            return execution.start(archive, id, runtime, token);
        }
        // Preserve the existing offline CLI contract: check the plan but never admit anonymously.
        try (var catalog = new ArchiveStore(archive)) {
            catalog.job(id);
        }
        return Map.of("passed", false, "exit_code", 7, "plan_id", id, "admission", "BLOCKED", "blocker",
            ADMISSION_BLOCKER, "network_requests", 0);
    }
}
