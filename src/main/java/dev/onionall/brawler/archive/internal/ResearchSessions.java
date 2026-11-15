package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.util.*;

/**
 * <b>Публичный bridge к ResearchCatalog для управления сессиями.</b>
 *
 * <p>Делегирует schema validation, persistence и revision management
 * заимствованному ResearchCatalog. Записи выполняются внутри
 * ArchiveContext transaction с проверкой capacity.
 *
 * <p>Не владеет JDBC lifecycle и не реализует собственный CAS.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ResearchSessions {
    private final ArchiveContext context;

    /**
     * <b>Привязывает compilation bridge к готовому research adapter.</b>
     * @param context владелец общего JDBC и transaction lifecycle
     */
    public ResearchSessions(ArchiveContext context) {
        this.context = context;
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
        return context.transaction(() -> {
            context.capacity();
            // ResearchCatalog видит autoCommit=false: его savepoint не коммитит
            // внешнюю transaction; DDL выполнен при открытии ArchiveContext.
            return context.research().create(document);
        });
    }

    /**
     * <b>Читает research document под общим JDBC ownership.</b>
     * <p>Чтение не изменяет revision и не создаёт новую версию документа.
     * @param id UUID сессии
     * @return проверенный document с SQL revision
     * @throws IOException при неизвестной сессии или ошибке чтения
     */
    public Map<String, Object> researchSession(String id) throws IOException {
        return context.serializableRead(() -> context.research().get(id));
    }

    /**
     * <b>Обновляет research document через CAS в общей transaction.</b>
     * <p>CAS предотвращает lost update: изменение применяется
     * только при совпадении ожидаемой revision с текущей SQL revision.
     * Автоматического merge конфликтующих изменений нет.
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
        return context.transaction(() -> {
            context.capacity();
            return context.research().update(id, revision, document);
        });
    }

    /**
     * <b>Проверяет наличие research session без mutation.</b>
     * @param id UUID сессии
     * @return существует ли session
     * @throws IOException при неверном id или ошибке JDBC
     */
    public boolean researchSessionExists(String id) throws IOException {
        return context.serializableRead(() -> context.research().exists(id));
    }
}
