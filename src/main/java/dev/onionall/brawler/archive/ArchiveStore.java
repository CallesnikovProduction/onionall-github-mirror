package dev.onionall.brawler.archive;

import dev.onionall.brawler.archive.internal.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * <b>Стабильный публичный API локального архива.</b>
 *
 * <p>ArchiveContext владеет JDBC и сериализует все операции, research и close. Фасад сохраняет JSON/API и не управляет SQL или blob publication.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ArchiveStore implements AutoCloseable {

    /** Максимальный размер одного HTML body в bytes. */
    public static final int MAX_HTML_BYTES = 131072;
    /** Максимальный суммарный размер retained objects в bytes, без автоматического удаления. */
    public static final long MAX_BLOB_BYTES = 64L * 1024 * 1024;

    /**
     * <b>Внутренний граф компонентов архивного хранилища.</b>
     *
     * <p>Объединяет зависимости {@link ArchiveStore} в неизменяемую
     * структуру. Не содержит бизнес-логики и не управляет ресурсами
     * самостоятельно: владение JDBC, транзакциями и синхронизацией
     * остаётся за {@link ArchiveContext}.
     *
     * @param context единый координатор доступа к H2 и жизненного цикла ресурсов
     * @param plans управление планами обхода и их утверждением
     * @param observations хранение наблюдений admission и выполнения заданий
     * @param graph хранение и чтение графа обнаруженных ссылок
     * @param snapshots сохранение страниц и истории HTML-снимков
     * @param failures регистрация ошибок и истории неудачных операций
     * @param queries поиск, экспорт и формирование архивных представлений
     * @param research управление исследовательскими сессиями
     *
     * @author Stephan Kolesnikov
     */
    record ArchiveStoreNode(
            ArchiveContext context,
            PlanRepository plans,
            ObservationRepository observations,
            GraphRepository graph,
            SnapshotRepository snapshots,
            FailureRepository failures,
            ArchiveQueryService queries,
            ResearchSessions research
    ) {
    };

    /**
     * Граф внутренних компонентов, обслуживающих публичный API архива.
     *
     * <p>Инициализируется при создании {@link ArchiveStore}
     * и сохраняется на протяжении его жизненного цикла.
     */
    private final ArchiveStoreNode archiveNode;

    private final DocumentRepository documents;
    private final ResearchExportService researchExports;

    /**
     * <b>Открывает embedded H2 и каталог immutable HTML.</b>
     *
     * <p>Создаёт каталоги, проверяет пути и версию схемы, выполняет additive DDL.
     * Соединением владеет экземпляр; AUTO_SERVER отключён. Закройте экземпляр после операции.
     *
     * @param location путь локального архива
     * @throws IOException при ошибке файлов, H2 или несовместимой схеме
     * @Refactor: Stephan Kolesnikov
     */
    public ArchiveStore(Path location) throws IOException {
        ArchiveContext context = new ArchiveContext(location);

        PlanRepository plans = new PlanRepository(context);
        ObservationRepository observations =
                new ObservationRepository(context, plans);

        GraphRepository graph = new GraphRepository(context, plans);
        SnapshotRepository snapshots =
                new SnapshotRepository(context, plans, graph);

        FailureRepository failures =
                new FailureRepository(context, plans);

        ArchiveQueryService queries =
                new ArchiveQueryService(
                        context, plans, observations, snapshots, graph
                );

        ResearchSessions research = new ResearchSessions(context);

        this.documents = new DocumentRepository(context, plans);
        this.researchExports = new ResearchExportService(context, snapshots, documents, plans);
        this.archiveNode = new ArchiveStoreNode(
                context, plans, observations, graph,
                snapshots, failures, queries, research
        );
    }

    /**
     * <b>Создаёт research document в общей catalog transaction.</b>
     * @param document schema-version 1 document с session_id
     * @return сохранённый document revision 0
     * @throws IOException при неверном document, capacity или persistence failure
     */
    public Map<String, Object> createResearchSession(
            Map<String, ? extends Object> document
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.research.createResearchSession(document)
        );
    }

    /**
     * <b>Читает research document под общим JDBC ownership.</b>
     * @param id UUID сессии
     * @return проверенный document с SQL revision
     * @throws IOException при неизвестной сессии или ошибке чтения
     */
    public Map<String, Object> researchSession(
            String id
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.research.researchSession(id)
        );
    }

    /**
     * <b>Обновляет research document через CAS в общей transaction.</b>
     * @param id UUID сессии
     * @param revision ожидаемая revision
     * @param document новое содержимое с прежней identity
     * @return сохранённый document следующей revision
     * @throws IOException при CAS conflict, нарушении bounds или persistence failure
     */
    public Map<String, Object> updateResearchSession(
            String id,
            long revision,
            Map<String, ? extends Object> document
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.research.updateResearchSession(id, revision, document)
        );
    }

    /**
     * <b>Проверяет наличие research session без mutation.</b>
     * @param id UUID сессии
     * @return существует ли session
     * @throws IOException при неверном id или ошибке JDBC
     */
    public boolean researchSessionExists(
            String id
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.research.researchSessionExists(id)
        );
    }

    /**
     * <b>Сохраняет неподтверждённый план обхода.</b>
     *
     * <p>Проверяет same-origin seeds, глубину и бюджет; не резервирует URL в Frontier.
     *
     * @param seeds разрешённые начальные URL
     * @param depth глубина обнаружения
     * @param maxPages лимит страниц
     * @param provider атрибуция источника
     * @return карта сохранённого плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> createPlan(
        List<String> seeds,
        int depth,
        int maxPages,
        String provider
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.plans.createPlan(seeds, depth, maxPages, provider)
        );
    }

    /**
     * <b>Читает bounded keyset список планов.</b>
     * <p>Live membership, не frozen snapshot и не scheduler queue.
     * @param after предыдущий plan_id либо null
     * @param limit максимум 1..100 планов
     * @return plans, next_cursor и признак truncated
     * @throws IOException при invalid cursor/limit или ошибке чтения
     */
    public Map<String, Object> plans(
            String after,
            int limit
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.plans.plans(after, limit)
        );
    }

    /**
     * <b>Читает план из каталога.</b>
     *
     * <p>Проверяет идентификатор; неизвестный план вызывает IOException.
     *
     * @param id идентификатор записи
     * @return карта плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> plan(String id) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.plans.plan(id)
        );
    }

    /**
     * <b>Сохраняет явное подтверждение плана.</b>
     *
     * <p>Изменяет approval в H2 без сетевого admission.
     *
     * @param id идентификатор записи
     * @return обновлённая карта плана
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> approvePlan(String id) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.plans.approvePlan(id)
        );
    }

    /**
     * <b>Сохраняет cached admission observation без изменения specification.</b>
     * <p>Context сериализует mutation; Frontier остаётся authority. Matching plan_id
     * разрешён для существующих worker callers, conflicting authority fields отклоняются.
     * @param id UUID подтверждённого плана
     * @param observation JSON object bounded после сериализации
     * @throws IOException при invalid observation, отсутствии approval или persistence failure
     */
    public void recordAdmission(
            String id,
            Map<String, Object> observation
    ) throws IOException {
        this.archiveNode.context.serializableRead(() -> {
            this.archiveNode.observations.recordAdmission(id, observation);
            return null;
        });
    }

    /**
     * <b>Сохраняет bounded observational status audit.</b>
     * <p>Context transaction сохраняет до 32 transitions; не изменяет Frontier.
     * @param id UUID плана
     * @param status job_status_version 1 с matching plan_id
     * @throws IOException при неверном status или persistence failure
     */
    public void recordJobStatus(
            String id,
            Map<String, Object> status
    ) throws IOException {
        this.archiveNode.context.serializableRead(() -> {
            this.archiveNode.observations.recordJobStatus(id, status);
            return null;
        });
    }

    /**
     * <b>Проверяет legacy launch evidence во всём каталоге.</b>
     * <p>Чтение под context ownership, без scheduler authority и migration.
     * @return найдено ли сохранённое execution evidence
     * @throws IOException при malformed observation или недоступном каталоге
     */
    public boolean hasExecutionHistory() throws IOException {
        return this.archiveNode.context.serializableRead(
                this.archiveNode.observations::hasExecutionHistory
        );
    }

    /**
     * <b>Читает сохранённое admission-наблюдение.</b>
     *
     * <p>Добавляет отметку времени и источник authority; отсутствие записи возвращает пустую карту.
     *
     * @param id идентификатор записи
     * @return сохранённое наблюдение либо пустая карта
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> admissionObservation(String id) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.observations.admissionObservation(id)
        );
    }

    /**
     * <b>Объединяет план и локальные наблюдения.</b>
     *
     * <p>Читает bounded failure summary и счётчики каталога; они не доказывают завершение Frontier.
     *
     * @param id идентификатор записи
     * @return карта задания
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> job(String id) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.queries.job(id)
        );
    }

    /**
     * <b>Сохраняет snapshot успешной crawler-загрузки.</b>
     *
     * <p>Проверяет approval, scope, статус и bounded HTML; объект публикуется до commit.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param depth глубина обнаружения
     * @param source источник обнаружения
     * @param status статус либо карта статуса
     * @param contentType тип содержимого
     * @param html байты HTML
     * @param links наблюдения связей
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> archive(
        String jobId,
        String url,
        int depth,
        String source,
        int status,
        String contentType,
        byte[] html,
        List<Map<String, Object>> links
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.snapshots.archive(
                        jobId, url, depth, source, status,
                        contentType, html, links)
        );
    }

    /**
     * <b>Сохраняет HTML ручного импорта.</b>
     *
     * <p>Использует MANUAL_IMPORT, http_status=0 и не подтверждает transport или robots.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param html байты HTML
     * @param links наблюдения связей
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> importHtml(
        String jobId,
        String url,
        byte[] html,
        List<Map<String, Object>> links
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.snapshots.importHtml(jobId, url, html, links)
        );
    }

    /**
     * <b>Читает bounded список последних snapshots.</b>
     *
     * <p>Не открывает HTML и не запрашивает Frontier.
     *
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта списка страниц
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> pages(String jobId, int limit) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.snapshots.pages(jobId, limit)
        );
    }

    /**
     * <b>Читает страницу, provenance и историю snapshots.</b>
     *
     * <p>Проверяет идентификатор и сохранённую страницу; HTML не исполняется.
     *
     * @param pageId идентификатор архивной страницы
     * @return метаданные страницы
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> page(String pageId) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.snapshots.page(pageId)
        );
    }

    /**
     * <b>Читает bounded historical directed edges одного job.</b>
     * <p>Сохраняет rejected/foreign targets; target_page разрешается внутри job.
     * @param jobId UUID плана
     * @param limit максимум 1..100 связей
     * @return job_id, links и limit
     * @throws IOException при invalid input или ошибке чтения
     */
    public Map<String, Object> links(String jobId, int limit) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.graph.links(jobId, limit)
        );
    }

    /**
     * <b>Ищет по URL, title и extracted_text каталога.</b>
     *
     * <p>Использует literal LOCATE и bounded результат; не вызывает search provider.
     *
     * @param query поисковая строка
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта результатов
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> search(
            String query,
            String jobId,
            int limit
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.queries.search(query, jobId, limit)
        );
    }

    /**
     * <b>Добавляет точный onion-host фильтр к существующему catalog query.</b>
     * <p>Фильтры применяются в SQL до LIMIT, без schema/index migration.
     * @param query literal подстрока URL, title или text
     * @param jobId задание либо null для общего каталога
     * @param host точный onion v3 hostname либо null
     * @param limit максимум 1..100 результатов
     * @return bounded результаты каталога
     * @throws IOException при некорректном фильтре или ошибке чтения
     * @author GPT-6 Astra
     */
    public Map<String, Object> search(
            String query,
            String jobId,
            String host,
            int limit
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.queries.search(query, jobId, host, limit)
        );
    }

    /**
     * <b>Формирует bounded экспорт локальных метаданных.</b>
     *
     * <p>Читает страницы и граф из каталога; файл публикует вызывающий код.
     *
     * @param jobId идентификатор задания
     * @param limit максимальный размер результата
     * @return карта экспортируемых данных
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> exportSummary(
            String jobId,
            int limit
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.queries.exportSummary(jobId, limit)
        );
    }

    /**
     * <b>Сохраняет агрегат ошибки и структурированное наблюдение.</b>
     *
     * <p>Транзакция ограничена catalog_guard; наблюдение не равно отдельной сетевой попытке.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param category нормализованная категория ошибки
     * @return идентификатор агрегата ошибки, job_id, URL и категория
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> recordFailure(
        String jobId,
        String url,
        String category
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.failures.recordFailure(jobId, url, category)
        );
    }

    /**
     * <b>Сохраняет агрегат ошибки и структурированное наблюдение.</b>
     *
     * <p>Транзакция ограничена catalog_guard; наблюдение не равно отдельной сетевой попытке.
     *
     * @param jobId идентификатор задания
     * @param url URL страницы
     * @param category нормализованная категория ошибки
     * @param observation структурированное наблюдение
     * @return идентификатор агрегата ошибки, job_id, URL и категория
     * @throws IOException при ошибке операции или нарушении контракта
     */
    public Map<String, Object> recordFailure(
        String jobId,
        String url,
        String category,
        FailureObservation observation
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.failures.recordFailure(jobId, url, category, observation)
        );
    }

    /**
     * <b>Читает immutable failure observations с фиксированным through_sequence.</b>
     * <p>Под context ownership; sequence не является fetch counter или scheduler queue.
     * @param jobId UUID плана
     * @param cursor job-scoped continuation либо null
     * @param limit максимум 1..100 observations
     * @return rows, nullable next_cursor и исторические counts
     * @throws IOException при invalid cursor/limit или ошибке чтения
     */
    public Map<String, Object> failureHistory(
            String jobId,
            String cursor,
            int limit
    ) throws IOException {
        return this.archiveNode.context.serializableRead(() ->
                this.archiveNode.failures.failureHistory(jobId, cursor, limit)
        );
    }

    @Override public void close() throws IOException {
        this.archiveNode.context.close();
    }

    /**
     * <b>Импортирует явный UTF-8 Markdown/plain text; HTML остаётся HTML snapshot.</b>
     * @param jobId подтверждённый план
     * @param url approved source URL
     * @param contentType явный поддерживаемый Content-Type
     * @param content исходные bounded bytes
     * @return committed metadata; import не доказывает сетевой fetch
     * @throws IOException при validation/persistence failure
     */
    public Map<String, Object> importDocument(String jobId, String url, String contentType,
            byte[] content) throws IOException {
        return archiveNode.context.serializableRead(() -> {
            if (contentType != null && contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/html")) {
                return archiveNode.snapshots.importHtml(jobId, url, content, List.of());
            }
            return documents.save(jobId, url, 0, url, 0, contentType, content, true);
        });
    }

    /**
     * <b>Архивирует уже полученный text response, не ослабляя transport policy.</b>
     * @param jobId подтверждённый план
     * @param url approved source URL
     * @param depth фактическая глубина
     * @param source approved referrer
     * @param status успешный HTTP status
     * @param contentType text/plain либо text/markdown с UTF-8
     * @param content bounded decoded response bytes
     * @return committed document version
     * @throws IOException при validation/persistence failure
     */
    public Map<String, Object> archiveDocument(String jobId, String url, int depth, String source,
            int status, String contentType, byte[] content) throws IOException {
        return archiveNode.context.serializableRead(() -> documents.save(jobId, url, depth, source,
            status, contentType, content, false));
    }

    /**
     * <b>Читает verified text/HTML research evidence с format и structural projection.</b>
     * @param documentId text-document/page UUID
     * @return lossless text либо структурированное HTML evidence
     * @throws IOException при неизвестной записи/повреждении
     */
    public Map<String, Object> researchDocument(String documentId) throws IOException {
        return researchExports.document(documentId);
    }

    /**
     * <b>Создаёт согласованный Markdown/JSON research bundle.</b>
     * @param jobId UUID плана
     * @param destination новый каталог, parent которого уже существует
     * @param limit максимум100 documents
     * @return complete versioned manifest
     * @throws IOException при отказе capture или записи; incomplete bundle не имеет manifest
     */
    public Map<String, Object> exportResearch(String jobId, Path destination, int limit) throws IOException {
        return researchExports.export(jobId, destination, limit);
    }
}
