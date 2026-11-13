package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantJobContextTest {
    @Test
    void requiresAuthoritativeOrganizationForProjectJobs() {
        assertThrows(IllegalArgumentException.class, () -> new TenantJobContext(
                "RUN-1", null, "project-a", "user-a", "TEST_RUN", "TEST_CASE", "case-a",
                Instant.now().toString(), "TENANT_PROJECT_TEST_EXECUTION_V1"));
    }

    @Test
    void platformJobsHaveExplicitNonTenantPolicy() {
        TenantJobContext context = new TenantJobContext("RUN-2", null, null, "admin-a", "TEST_RUN",
                "TEST_RUN", "RUN-2", Instant.now().toString(), "PLATFORM_ADMIN_RUN");
        assertEquals(true, context.platformJob());
        assertEquals("admin-a", context.requesterUserId());
    }

    @Test
    void tenantContextIsExplicitAcrossThreadPoolReuse() throws Exception {
        TenantJobContext a = context("RUN-A", "project-a", "user-a");
        TenantJobContext b = context("RUN-B", "project-b", "user-b");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch barrier = new CountDownLatch(2);
        try {
            var first = pool.submit(() -> { barrier.countDown(); barrier.await(5, TimeUnit.SECONDS); return a.projectId(); });
            var second = pool.submit(() -> { barrier.countDown(); barrier.await(5, TimeUnit.SECONDS); return b.projectId(); });
            assertEquals("project-a", first.get(5, TimeUnit.SECONDS));
            assertEquals("project-b", second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tamperedProjectOrOrganizationCannotBeRepresentedAsTheSameJob() {
        TenantJobContext original = context("RUN-A", "project-a", "user-a");
        TenantJobContext tampered = new TenantJobContext(original.jobId(),
                UUID.randomUUID(), "project-a", original.requesterUserId(), original.jobType(),
                original.resourceType(), original.resourceId(), original.createdAt(), original.authorizationPolicy());
        // The immutable record has no mutator or fallback lookup: a changed owner is a different value.
        assertEquals(false, original.equals(tampered));
    }

    private static TenantJobContext context(String id, String project, String user) {
        return new TenantJobContext(id, UUID.randomUUID(), project, user, "TEST_RUN", "TEST_CASE", id,
                Instant.now().toString(), "TENANT_PROJECT_TEST_EXECUTION_V1");
    }
}
