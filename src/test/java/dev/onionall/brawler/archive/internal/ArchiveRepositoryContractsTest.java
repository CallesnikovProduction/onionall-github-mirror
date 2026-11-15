package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * <b>Проверяет ownership прямых PlanRepository readers.</b>
 *
 * <p>Репозиторий не начинает скрытую транзакцию чтения: valid readers требуют
 * monitor context, а закрытый владелец запрещает доступ до обращения к JDBC.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public class ArchiveRepositoryContractsTest {
    private static final String URL =
        "http://aeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaeaqcaibaea37ead.onion/";

    @Test public void directValidReadersRejectMissingContextOwnership() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-plan-owner"))) {
            var plans = new PlanRepository(context);
            String id = (String) plans.createPlan(List.of(URL), 1, 2, "contract-fixture").get("plan_id");
            assertThrows(IllegalStateException.class, () -> plans.plan(id));
            assertThrows(IllegalStateException.class, () -> plans.plans(null, 10));
            assertThrows(IllegalStateException.class, () -> plans.approved(id));
            assertEquals("DRAFT", context.serializableRead(() -> plans.plan(id)).get("state"));
        }
    }

    @Test public void ownedReadersRetainSpecificationApprovalAndDetachedResults() throws Exception {
        try (var context = new ArchiveContext(Files.createTempDirectory("archive-plan-contract"))) {
            var plans = new PlanRepository(context);
            String id = (String) plans.createPlan(List.of(URL), 1, 2, "contract-fixture").get("plan_id");
            var draft = context.serializableRead(() -> plans.plan(id));
            assertEquals(false, draft.get("approved"));
            assertEquals("UNOBSERVED", draft.get("admission"));
            assertThrows(IOException.class, () -> context.serializableRead(() -> plans.approved(id)));
            plans.approvePlan(id);
            var approved = context.serializableRead(() -> plans.approved(id));
            assertEquals(true, approved.get("approved"));
            assertEquals("APPROVED", approved.get("state"));
            assertEquals("UNOBSERVED", approved.get("admission"));
            assertEquals(List.of(URL), approved.get("seeds"));
            var listed = context.serializableRead(() -> plans.plans(null, 10));
            assertEquals(1, ((List<?>) listed.get("plans")).size());
            assertNull(listed.get("next_cursor"));
            approved.put("approved", false);
            approved.put("depth", 5);
            assertEquals(true, context.serializableRead(() -> plans.approved(id)).get("approved"));
            assertEquals(1, context.serializableRead(() -> plans.plan(id)).get("depth"));
        }
    }

    @Test public void closedContextRejectsOwnedReadersBeforeJdbcAccess() throws Exception {
        var context = new ArchiveContext(Files.createTempDirectory("archive-plan-closed"));
        var plans = new PlanRepository(context);
        String id = (String) plans.createPlan(List.of(URL), 0, 1, "contract-fixture").get("plan_id");
        context.close();
        assertThrows(IOException.class, () -> context.serializableRead(() -> plans.plan(id)));
        synchronized (context) {
            assertEquals("Archive is closed", assertThrows(IOException.class, () -> plans.plan(id)).getMessage());
            assertEquals("Archive is closed", assertThrows(IOException.class, () -> plans.plans(null, 10)).getMessage());
            assertEquals("Archive is closed", assertThrows(IOException.class, () -> plans.approved(id)).getMessage());
        }
        context.close();
    }
}
