package dev.onionall.brawler.admission;

import java.time.Instant;
import java.util.*;

/** Versioned additive view for CLI/future UI; cached values never drive lifecycle truth. */
final class JobStatusView {
    private JobStatusView() { }
    static Map<String,Object> assemble(Map<String,Object> catalog, FrontierReadback.Counts c,
                                       WorkerObservation.Observation worker, Boolean reachable,
                                       boolean requested, String catalogAt) {
        boolean admitted = catalog.get("authoritative_admission") instanceof Map<?,?>
            || "COMMITTED".equals(catalog.get("admission")) || "SERVER_READBACK".equals(catalog.get("admission"));
        var assessment = JobStatus.assess(Boolean.TRUE.equals(catalog.get("approved")), admitted, requested, c, worker.worker());
        Map<?,?> old = catalog.get("job_status") instanceof Map<?,?> m ? m : Map.of();
        Map<?,?> oldScheduler = old.get("scheduler") instanceof Map<?,?> m ? m : Map.of();
        Object cachedAt = oldScheduler.get("readback_at") != null ? oldScheduler.get("readback_at") : oldScheduler.get("cached_readback_at");
        Map<String,Object> counts = new LinkedHashMap<>();
        for (String key : List.of("reserved", "pending", "held", "scheduled_wait", "unscheduled", "unknown", "done", "success", "terminal", "retry_wait")) counts.put(key,null);
        counts.put("archived", catalog.get("archived_pages"));
        if(c!=null) {
            counts.put("reserved",c.reserved());counts.put("pending",c.pending());counts.put("held",c.held());
            counts.put("scheduled_wait",c.scheduledWait());counts.put("unscheduled",c.unscheduled());counts.put("unknown",c.unknown());
            counts.put("done",c.done());counts.put("success",c.success());counts.put("terminal",c.terminal());counts.put("retry_wait",c.retryWait());
        }
        Map<String,Object> scheduler = new LinkedHashMap<>();
        scheduler.put("reachable",reachable);scheduler.put("available",c!=null);
        scheduler.put("readback_at",c==null?null:c.readbackAt().toString());
        scheduler.put("cached_readback_at",c==null?cachedAt:null);
        scheduler.put("error",requested&&c==null?"AUTHORITATIVE_READBACK_UNAVAILABLE":null);
        Map<String,Object> view = new LinkedHashMap<>();
        view.put("job_status_version",1);view.put("plan_id",catalog.get("plan_id"));
        view.put("job_state",assessment.state().name());view.put("completion",assessment.completion().name());
        view.put("state_basis",assessment.basis());view.put("observed_at",Instant.now().toString());
        view.put("catalog_readback_at",catalogAt);view.put("worker",worker.details());view.put("scheduler",scheduler);view.put("counts",counts);
        view.put("authoritative_readback_current",c!=null);view.put("status_stale",c==null&&cachedAt!=null);
        view.put("admission_basis",c!=null?"SERVER_READBACK":admitted?"CACHED_FRONTIER_OBSERVATION":"CATALOG_PLAN_ONLY");
        view.put("last_authoritative_counts",c==null?old.get("last_authoritative_counts"):counts);
        view.put("next_attempt_at",c==null||c.nextAttemptAt()==null?null:c.nextAttemptAt().toString());
        view.put("resume_hint",assessment.state()==JobStatus.State.PAUSED?"crawl start --plan "+catalog.get("plan_id")+" --runtime <descriptor> --admission-token-file <private-file>":null);
        view.put("legacy_state",catalog.get("state"));view.put("state_semantics","LEGACY_EXECUTION_OBSERVATION");
        view.put("failure_records_truncated",catalog.get("failures") instanceof Number n && catalog.get("failure_records") instanceof List<?> rows && n.longValue()>rows.size());
        view.put("status_transition_limit",32);
        return view;
    }
}
