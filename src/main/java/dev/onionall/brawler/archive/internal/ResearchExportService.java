package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.archive.ArchiveResearchBundle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static dev.onionall.brawler.archive.internal.ArchiveSupport.*;

/**
 * <b>Guarded capture verified документов и последующий standalone export.</b>
 *
 * <p>JDBC и blob reads завершаются до filesystem output; manifest не является
 * scheduling/result authority. Capture bounded, omissions явно обозначены.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ResearchExportService {
    private final ArchiveContext context;
    private final SnapshotRepository snapshots;
    private final DocumentRepository documents;
    private final PlanRepository plans;

    /**
     * Связывает Archive read-side компоненты без собственного JDBC lifecycle.
     * @param context единый владелец persistence
     * @param snapshots HTML evidence
     * @param documents text evidence
     * @param plans плановые границы
     */
    public ResearchExportService(ArchiveContext context, SnapshotRepository snapshots,
            DocumentRepository documents, PlanRepository plans) {
        this.context = context;
        this.snapshots = snapshots;
        this.documents = documents;
        this.plans = plans;
    }

    /**
     * Читает latest text либо HTML как явно типизированное research evidence.
     * @param id text-document/page UUID
     * @return verified projection; unknown fields nullable
     * @throws IOException при неизвестном/повреждённом документе
     */
    public Map<String, Object> document(String id) throws IOException {
        return context.consistentRead(() -> {
            var text = documents.find(id);
            return text != null ? text : snapshots.researchDocument(id);
        });
    }

    /**
     * Экспортирует максимум100 verified latest documents; существующий destination запрещён.
     * @param jobId план
     * @param destination новый каталог
     * @param limit 1..100
     * @return complete manifest
     * @throws IOException при отказе validation/capture/export
     */
    public Map<String, Object> export(String jobId, Path destination, int limit) throws IOException {
        Capture capture = context.consistentRead(() -> {
            bound(limit); plans.plan(jobId);
            var candidates = new ArrayList<Map<String, Object>>();
            @SuppressWarnings("unchecked")
            var html = (List<Map<String, Object>>) snapshots.pages(jobId, 100).get("pages");
            candidates.addAll(html); candidates.addAll(documents.list(jobId, 100));
            candidates.sort(Comparator.<Map<String, Object>, java.time.Instant>comparing(
                row -> java.time.Instant.parse((String) row.get("recorded_at"))).thenComparing(row -> (String) row.get("page_id")));
            boolean truncated = candidates.size() > limit;
            var result = new ArrayList<Map<String, Object>>();
            for (var candidate : candidates.subList(0, Math.min(limit, candidates.size()))) {
                result.add(document((String) candidate.get("page_id")));
            }
            return new Capture(result, truncated);
        });
        return ArchiveResearchBundle.write(destination, jobId, capture.documents(), capture.truncated());
    }

    /** Immutable capture отделён от filesystem output lifecycle. */
    private record Capture(List<Map<String, Object>> documents, boolean truncated) {}
}
