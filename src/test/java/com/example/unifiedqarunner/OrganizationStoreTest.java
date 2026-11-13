package com.example.unifiedqarunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrganizationStoreTest {
    @Test
    void organizationValidatesUuidSlugStatusAndUtcTimestamps() throws Exception {
        Path root = Files.createTempDirectory("qa-org-model-");
        try {
            UUID owner = UUID.randomUUID();
            OrganizationStore store = new OrganizationStore(root);
            Organization organization = store.createOrganization("Acme Quality", "Acme-QA", owner.toString());
            assertEquals("acme-qa", organization.organizationSlug());
            assertEquals(OrganizationStatus.ACTIVE, organization.status());
            assertEquals(OrganizationStore.CURRENT_SCHEMA_VERSION, organization.schemaVersion());
            assertThrows(IllegalArgumentException.class, () -> new Organization(UUID.randomUUID(), "Name", "bad slug", owner.toString(),
                    OrganizationStatus.ACTIVE, Instant.now(), Instant.now(), 1));
            assertThrows(IllegalArgumentException.class, () -> new Organization(UUID.randomUUID(), "Name", "name", "not-a-uuid",
                    OrganizationStatus.ACTIVE, Instant.now(), Instant.now(), 1));
            assertThrows(IllegalArgumentException.class, () -> new Organization(UUID.randomUUID(), "Name", "name", owner.toString(),
                    OrganizationStatus.ACTIVE, Instant.now(), Instant.now().minusSeconds(1), 1));
        } finally { deleteTree(root); }
    }

    @Test
    void slugsAndMembershipsAreUnique() throws Exception {
        Path root = Files.createTempDirectory("qa-org-unique-");
        try {
            OrganizationStore store = new OrganizationStore(root);
            UUID owner = UUID.randomUUID();
            store.createOrganization("One", "one", owner.toString());
            assertThrows(IllegalArgumentException.class, () -> store.createOrganization("Duplicate", "ONE", UUID.randomUUID().toString()));
            UUID organizationId = store.organizations().get(0).organizationId();
            OrganizationMembership membership = new OrganizationMembership(UUID.randomUUID(), organizationId, UUID.randomUUID().toString(),
                    OrganizationRole.MEMBER, MembershipStatus.ACTIVE, Instant.now(), Instant.now());
            store.createMembership(membership);
            assertThrows(IllegalArgumentException.class, () -> store.createMembership(new OrganizationMembership(UUID.randomUUID(), organizationId,
                    membership.userId(), OrganizationRole.VIEWER, MembershipStatus.ACTIVE, Instant.now(), Instant.now())));
        } finally { deleteTree(root); }
    }

    @Test
    void invitationStoresOnlyHashExpiresAndCannotReplay() throws Exception {
        Path root = Files.createTempDirectory("qa-org-invite-");
        try {
            OrganizationStore store = new OrganizationStore(root);
            UUID owner = UUID.randomUUID();
            Organization organization = store.createOrganization("Invites", "invites", owner.toString());
            String rawToken = "temporary-secret-token-not-persisted";
            String invitee = UUID.randomUUID().toString();
            OrganizationInvitation invitation = store.createInvitation(organization.organizationId(), "person@example.test", invitee,
                    OrganizationRole.MEMBER, rawToken, Instant.now().plus(1, ChronoUnit.HOURS), owner.toString());
            assertNotEquals(rawToken, invitation.tokenHash());
            assertEquals(OrganizationValidation.hashToken(rawToken), invitation.tokenHash());
            assertFalse(Files.readString(store.dataPath()).contains(rawToken));
            assertThrows(IllegalStateException.class, () -> store.acceptInvitation(invitation.invitationId(), rawToken, invitee,
                    "person@example.test", Instant.now().plus(2, ChronoUnit.HOURS)));

            OrganizationInvitation valid = store.createInvitation(organization.organizationId(), "person2@example.test", UUID.randomUUID().toString(),
                    OrganizationRole.VIEWER, "another-temporary-token", Instant.now().plus(1, ChronoUnit.HOURS), owner.toString());
            OrganizationMembership membership = store.acceptInvitation(valid.invitationId(), "another-temporary-token",
                    valid.invitedUserId(), valid.invitedEmail(), Instant.now());
            assertEquals(MembershipStatus.ACTIVE, membership.membershipStatus());
            assertThrows(IllegalStateException.class, () -> store.acceptInvitation(valid.invitationId(), "another-temporary-token",
                    valid.invitedUserId(), valid.invitedEmail(), Instant.now()));
        } finally { deleteTree(root); }
    }

    @Test
    void ownershipIsImmutableAndLegacyProjectsRemainQuarantined() throws Exception {
        Path root = Files.createTempDirectory("qa-org-project-");
        try {
            OrganizationStore store = new OrganizationStore(root);
            UUID owner = UUID.randomUUID();
            Organization first = store.createOrganization("First", "first", owner.toString());
            Organization second = store.createOrganization("Second", "second", UUID.randomUUID().toString());
            store.quarantineProject("legacy-project");
            assertEquals(List.of("legacy-project"), store.quarantinedProjectIds());
            ProjectOrganizationOwnership assigned = store.assignProject("legacy-project", first.organizationId(), "EXPLICIT_REVIEW");
            assertTrue(store.quarantinedProjectIds().isEmpty());
            assertEquals(assigned, store.assignProject("legacy-project", first.organizationId(), "IGNORED_REASSIGNMENT"));
            assertThrows(IllegalStateException.class, () -> store.assignProject("legacy-project", second.organizationId(), "WRONG_TENANT"));
        } finally { deleteTree(root); }
    }

    @Test
    void lastOwnerCannotBeSuspended() throws Exception {
        Path root = Files.createTempDirectory("qa-org-owner-");
        try {
            OrganizationStore store = new OrganizationStore(root);
            Organization organization = store.createOrganization("Owners", "owners", UUID.randomUUID().toString());
            OrganizationMembership owner = store.memberships(organization.organizationId()).get(0);
            assertThrows(IllegalStateException.class, () -> store.updateMembership(owner.membershipId(), OrganizationRole.MEMBER, MembershipStatus.ACTIVE));
        } finally { deleteTree(root); }
    }

    @Test
    void missingAndOlderSchemaVersionsUpgradeWithoutDroppingUnknownFields() throws Exception {
        Path root = Files.createTempDirectory("qa-org-schema-");
        try {
            Path data = root.resolve(OrganizationStore.FILE_NAME);
            Files.writeString(data, "{\"legacyMarker\":\"preserve-me\",\"organizations\":[],\"organizationSchemaVersion\":0}");
            OrganizationStore store = new OrganizationStore(root);
            store.createOrganization("Compatible", "compatible", UUID.randomUUID().toString());
            String persisted = Files.readString(data);
            assertTrue(persisted.contains("preserve-me"));
            assertTrue(persisted.contains("organizationSchemaVersion"));
            assertTrue(Files.size(data) > 0);
        } finally { deleteTree(root); }
    }

    @Test
    void futureSchemaAndCorruptJsonAreRejectedWithoutOverwrite() throws Exception {
        Path root = Files.createTempDirectory("qa-org-invalid-");
        try {
            Path data = root.resolve(OrganizationStore.FILE_NAME);
            String future = "{\"organizationSchemaVersion\":999,\"future\":true}";
            Files.writeString(data, future);
            OrganizationStore store = new OrganizationStore(root);
            assertThrows(OrganizationPersistenceException.class, store::organizations);
            assertEquals(future, Files.readString(data));
            String corrupt = "not-json";
            Files.writeString(data, corrupt);
            assertThrows(OrganizationPersistenceException.class, store::organizations);
            assertEquals(corrupt, Files.readString(data));
        } finally { deleteTree(root); }
    }

    @Test
    void writesCreateRecoveryBackupAndConcurrentUpdatesDoNotLoseRecords() throws Exception {
        Path root = Files.createTempDirectory("qa-org-concurrency-");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            OrganizationStore store = new OrganizationStore(root);
            store.createOrganization("Atomic", "atomic", UUID.randomUUID().toString());
            store.updateOrganization(store.organizations().get(0).organizationId(), "Atomic Updated", "atomic-updated", OrganizationStatus.ACTIVE);
            assertTrue(Files.exists(store.backupPath()));
            assertTrue(Files.readString(store.backupPath()).contains("Atomic"));
            OrganizationStore one = new OrganizationStore(root);
            OrganizationStore two = new OrganizationStore(root);
            List<Callable<Void>> work = List.of(() -> { one.quarantineProject("p-one"); return null; },
                    () -> { two.quarantineProject("p-two"); return null; });
            List<Future<Void>> results = executor.invokeAll(work);
            for (Future<Void> result : results) result.get();
            assertEquals(2, store.quarantinedProjectIds().size());
            assertTrue(store.quarantinedProjectIds().containsAll(List.of("p-one", "p-two")));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
    }
}
