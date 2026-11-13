package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LegacyMigrationReviewServiceTest {
    @Test
    void inventoryIsReadOnlyAndUnassignedProjectsAreQuarantined() throws Exception {
        Fixture fixture = new Fixture();
        try {
            String before = Files.readString(fixture.root.resolve("qa-runner.json"));
            List<LegacyMigrationReviewService.LegacyProjectInventory> inventory = fixture.service.inventory(fixture.admin.id());
            assertEquals(1, inventory.size());
            assertEquals(LegacyMigrationReviewService.AssignmentStatus.UNASSIGNED, inventory.get(0).assignmentStatus());
            assertTrue(inventory.get(0).quarantined());
            assertTrue(inventory.get(0).ambiguityReason().contains("No authoritative"));
            assertEquals(before, Files.readString(fixture.root.resolve("qa-runner.json")));
            assertTrue(fixture.store.projectOwnerships().isEmpty());
            assertThrows(TenantAuthorizationException.class, () -> fixture.service.inventory(fixture.viewer.id()));
        } finally { fixture.close(); }
    }

    @Test
    void approvedMigrationRequiresEvidenceAndCreatesVerifiedBackupAndAudit() throws Exception {
        Fixture fixture = new Fixture();
        try {
            var proposal = fixture.service.propose(fixture.admin.id(), fixture.project.id(), fixture.organization.organizationId(),
                    "ADMIN_REVIEW", "Verified active project membership and explicit owner approval.");
            assertTrue(fixture.store.findProjectOwnership(fixture.project.id()) == null);
            assertTrue(fixture.store.quarantinedProjectIds().contains(fixture.project.id()));
            var approved = fixture.service.approve(fixture.admin.id(), proposal.proposalId());
            assertEquals(LegacyMigrationReviewService.ProposalStatus.APPROVED, approved.status());
            assertEquals(fixture.organization.organizationId(), fixture.store.findProjectOwnership(fixture.project.id()).organizationId());
            assertFalse(fixture.store.quarantinedProjectIds().contains(fixture.project.id()));
            assertTrue(fixture.service.audit(fixture.admin.id()).stream().anyMatch(item -> "BACKUP_VERIFIED".equals(item.event())));
            assertTrue(fixture.service.audit(fixture.admin.id()).stream().anyMatch(item -> "ASSIGNMENT_APPROVED".equals(item.event())));
            assertTrue(Files.exists(fixture.root.resolve(".qa-migration-backups")));
        } finally { fixture.close(); }
    }

    @Test
    void backupFailureAbortsWithoutAssignmentAndConflictsAreRejected() throws Exception {
        Fixture fixture = new Fixture();
        try {
            var failing = fixture.serviceWith(new FailingBackup(), () -> { });
            var proposal = failing.propose(fixture.admin.id(), fixture.project.id(), fixture.organization.organizationId(), "REVIEW", "Evidence");
            assertThrows(IOException.class, () -> failing.approve(fixture.admin.id(), proposal.proposalId()));
            assertTrue(fixture.store.findProjectOwnership(fixture.project.id()) == null);
            assertTrue(failing.audit(fixture.admin.id()).stream().anyMatch(item -> "BACKUP_FAILED".equals(item.event())));

            Organization other = fixture.store.createOrganization("Other", "other", fixture.viewer.id());
            fixture.store.assignProject(fixture.project.id(), fixture.organization.organizationId(), "EXPLICIT_TEST_ASSIGNMENT");
            assertThrows(IllegalStateException.class, () -> fixture.store.assignProject(fixture.project.id(), other.organizationId(), "CONFLICT"));
            assertThrows(IllegalStateException.class, () -> fixture.service.propose(fixture.admin.id(), fixture.project.id(), other.organizationId(), "REVIEW", "Conflict"));
        } finally { fixture.close(); }
    }

    @Test
    void partialFailureIsCompensatedAndRetryIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SnapshotBackup backup = new SnapshotBackup(fixture.store.dataPath());
            var failing = fixture.serviceWith(backup, () -> { throw new IOException("simulated post-assignment failure"); });
            var proposal = failing.propose(fixture.admin.id(), fixture.project.id(), fixture.organization.organizationId(), "REVIEW", "Evidence");
            assertThrows(IllegalStateException.class, () -> failing.approve(fixture.admin.id(), proposal.proposalId()));
            assertTrue(fixture.store.findProjectOwnership(fixture.project.id()) == null);
            assertTrue(failing.audit(fixture.admin.id()).stream().anyMatch(item -> "ASSIGNMENT_RECOVERED".equals(item.event())));

            var retry = fixture.serviceWith(new SnapshotBackup(fixture.store.dataPath()), () -> { });
            var retryProposal = retry.propose(fixture.admin.id(), fixture.project.id(), fixture.organization.organizationId(), "REVIEW", "Retry after recovery");
            assertEquals(LegacyMigrationReviewService.ProposalStatus.APPROVED, retry.approve(fixture.admin.id(), retryProposal.proposalId()).status());
            assertEquals(LegacyMigrationReviewService.ProposalStatus.APPROVED, retry.approve(fixture.admin.id(), retryProposal.proposalId()).status());
        } finally { fixture.close(); }
    }

    @Test
    void concurrentProposalsCannotCreateTwoPlansForOneProject() throws Exception {
        Fixture fixture = new Fixture();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> work = () -> {
                try { return fixture.service.propose(fixture.admin.id(), fixture.project.id(), fixture.organization.organizationId(), "REVIEW", "Concurrent review"); }
                catch (Exception error) { return error; }
            };
            List<Future<Object>> results = executor.invokeAll(List.of(work, work));
            List<Object> values = results.stream().map(value -> {
                try { return value.get(5, TimeUnit.SECONDS); }
                catch (Exception error) { return error; }
            }).toList();
            assertEquals(1, values.stream().filter(value -> value instanceof LegacyMigrationReviewService.MigrationProposal).count());
            assertEquals(1, values.stream().filter(value -> value instanceof IllegalStateException).count());
        } finally { executor.shutdownNow(); fixture.close(); }
    }

    private static final class Fixture {
        final Path root;
        final AuthUserDirectory.User admin;
        final AuthUserDirectory.User viewer;
        final OrganizationStore store;
        final UserStoryStore projects;
        final UserStoryStore.Project project;
        final Organization organization;
        final TeamMemberManager members;
        final ProjectOwnershipManager ownership;
        final LegacyMigrationReviewService service;

        Fixture() throws Exception {
            root = Files.createTempDirectory("qa-legacy-migration-");
            Files.writeString(root.resolve("qa-runner.json"), "{}");
            AuthUserDirectory users = new AuthUserDirectory(root);
            admin = users.create("Migration Admin", "migrationadmin", "migrationadmin@example.test", "", "temporary-migration-password".toCharArray(), true);
            viewer = users.create("Viewer", "viewer", "viewer@example.test", "", "temporary-viewer-password".toCharArray(), true);
            projects = new UserStoryStore(root);
            project = projects.createProject("LEG", "Legacy Project", "Legacy", admin.username());
            store = new OrganizationStore(root);
            organization = store.createOrganization("Target", "target", admin.id());
            members = new TeamMemberManager(root);
            TeamRoleManager roles = new TeamRoleManager(root);
            members.create(Map.of("name", admin.fullName(), "email", admin.email(), "username", admin.username(),
                    "registeredUserId", admin.id(), "role", "Admin", "projectIds", List.of(project.id())), Set.of(project.id()), roles.list());
            members.create(Map.of("name", viewer.fullName(), "email", viewer.email(), "username", viewer.username(),
                    "registeredUserId", viewer.id(), "role", "Viewer", "projectIds", List.of()), Set.of(project.id()), roles.list());
            ownership = new ProjectOwnershipManager(root);
            service = new LegacyMigrationReviewService(root, projects, members, ownership, store);
        }

        LegacyMigrationReviewService serviceWith(LegacyMigrationReviewService.BackupCoordinator backup,
                                                 LegacyMigrationReviewService.FailureHook hook) {
            return new LegacyMigrationReviewService(root, projects, members, ownership, store, backup, hook);
        }

        void close() throws Exception {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static final class FailingBackup implements LegacyMigrationReviewService.BackupCoordinator {
        public LegacyMigrationReviewService.BackupHandle create(String planId) throws IOException { throw new IOException("backup unavailable"); }
        public void markPostMutation(LegacyMigrationReviewService.BackupHandle handle) { }
        public void restore(LegacyMigrationReviewService.BackupHandle handle) { }
    }

    private static final class SnapshotBackup implements LegacyMigrationReviewService.BackupCoordinator {
        private final Path source;
        private Path snapshot;

        SnapshotBackup(Path source) { this.source = source; }
        public LegacyMigrationReviewService.BackupHandle create(String planId) throws IOException {
            snapshot = Files.createTempFile("qa-migration-snapshot-", ".json");
            if (Files.exists(source)) Files.copy(source, snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new LegacyMigrationReviewService.BackupHandle(planId, snapshot);
        }
        public void markPostMutation(LegacyMigrationReviewService.BackupHandle handle) { }
        public void restore(LegacyMigrationReviewService.BackupHandle handle) throws IOException {
            if (snapshot != null && Files.exists(snapshot)) Files.copy(snapshot, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
