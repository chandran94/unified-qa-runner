package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Internal, platform-admin-only workflow for reviewing legacy project tenant
 * assignments. Inventory never mutates ownership; approval is explicit and
 * backup-coordinated. This class is deliberately not exposed as an HTTP route.
 */
final class LegacyMigrationReviewService {
    static final int CURRENT_SCHEMA_VERSION = 1;
    static final String FILE_NAME = ".qa-migration-review.json";

    enum AssignmentStatus { ASSIGNED, UNASSIGNED, CONFLICTING }
    enum ProposalStatus { PENDING, APPROVED, REJECTED, RECOVERED }

    record OwnershipEvidence(String type, String subjectId, String detail) { }

    record LegacyProjectInventory(String projectId, String projectName,
                                  List<OwnershipEvidence> trustworthyEvidence,
                                  AssignmentStatus assignmentStatus,
                                  boolean quarantined, String ambiguityReason) {
        LegacyProjectInventory {
            trustworthyEvidence = List.copyOf(trustworthyEvidence == null ? List.of() : trustworthyEvidence);
        }
    }

    record MigrationProposal(String proposalId, String projectId, UUID organizationId,
                             String actorUserId, String source, String reason,
                             ProposalStatus status, Instant createdAt, Instant approvedAt) { }

    record MigrationAudit(String event, String proposalId, String projectId,
                          String organizationId, String actorUserId, String source,
                          String reason, String backupPlanId, Instant timestamp) { }

    interface BackupCoordinator {
        BackupHandle create(String planId) throws IOException;
        void markPostMutation(BackupHandle handle) throws IOException;
        void restore(BackupHandle handle) throws IOException;
    }

    interface FailureHook { void afterAssignment() throws IOException; }

    record BackupHandle(String planId, Path manifestPath) { }

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final Path reviewPath;
    private final Path lockPath;
    private final UserStoryStore projects;
    private final TeamMemberManager teamMembers;
    private final ProjectOwnershipManager resourceOwnership;
    private final OrganizationStore organizations;
    private final BackupCoordinator backups;
    private final FailureHook failureHook;
    private final ReentrantLock jvmLock;

    LegacyMigrationReviewService(Path root, UserStoryStore projects, TeamMemberManager teamMembers,
                                 ProjectOwnershipManager resourceOwnership, OrganizationStore organizations) {
        this(root, projects, teamMembers, resourceOwnership, organizations,
                new FileBackupCoordinator(root, organizations), () -> { });
    }

    LegacyMigrationReviewService(Path root, UserStoryStore projects, TeamMemberManager teamMembers,
                                 ProjectOwnershipManager resourceOwnership, OrganizationStore organizations,
                                 BackupCoordinator backups, FailureHook failureHook) {
        this.root = root.toAbsolutePath().normalize();
        this.reviewPath = this.root.resolve(FILE_NAME);
        this.lockPath = this.root.resolve(FILE_NAME + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        this.projects = projects;
        this.teamMembers = teamMembers;
        this.resourceOwnership = resourceOwnership;
        this.organizations = organizations;
        this.backups = backups;
        this.failureHook = failureHook;
    }

    /** Sensitive inventory; callers must already be an active platform migration administrator. */
    synchronized List<LegacyProjectInventory> inventory(String actorUserId) throws IOException {
        requireMigrationAdmin(actorUserId);
        Set<String> quarantined = Set.copyOf(organizations.quarantinedProjectIds());
        List<ProjectOrganizationOwnership> assignments = organizations.projectOwnerships();
        List<ProjectOwnershipManager.Ownership> resources = resourceOwnership.list();
        List<TeamMemberManager.Member> members = teamMembers.list();
        return projects.listProjects(true).stream().map(project -> {
            ProjectOrganizationOwnership assignment = assignments.stream()
                    .filter(item -> project.id().equals(item.projectId())).findFirst().orElse(null);
            List<OwnershipEvidence> evidence = new ArrayList<>();
            members.stream().filter(member -> TeamMemberManager.ACTIVE.equals(member.status())
                    && member.projectIds().contains(project.id()))
                    .forEach(member -> evidence.add(new OwnershipEvidence("ACTIVE_PROJECT_MEMBERSHIP",
                            member.registeredUserId(), member.role())));
            resources.stream().filter(item -> ProjectOwnershipManager.RESOLVED.equals(item.status())
                    && project.id().equals(item.projectId())).limit(100)
                    .forEach(item -> evidence.add(new OwnershipEvidence("RESOLVED_RESOURCE_OWNERSHIP",
                            item.resourceType() + ":" + item.resourceId(), item.source())));
            AssignmentStatus status = assignment == null ? AssignmentStatus.UNASSIGNED : AssignmentStatus.ASSIGNED;
            boolean isQuarantined = assignment == null || quarantined.contains(project.id());
            String ambiguity = assignment == null
                    ? "No authoritative project-to-organization assignment exists; project remains quarantined."
                    : "Already assigned; no migration is required.";
            return new LegacyProjectInventory(project.id(), project.name(), evidence, status, isQuarantined, ambiguity);
        }).toList();
    }

    synchronized MigrationProposal propose(String actorUserId, String projectId, UUID organizationId,
                                            String source, String reason) throws IOException {
        jvmLock.lock();
        try { return proposeLocked(actorUserId, projectId, organizationId, source, reason); }
        finally { jvmLock.unlock(); }
    }

    private MigrationProposal proposeLocked(String actorUserId, String projectId, UUID organizationId,
                                             String source, String reason) throws IOException {
        requireMigrationAdmin(actorUserId);
        String normalizedProject = required(projectId, "projectId");
        String normalizedSource = required(source, "source");
        String normalizedReason = required(reason, "reason");
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required.");
        UserStoryStore.Project project = projects.listProjects(true).stream()
                .filter(item -> normalizedProject.equals(item.id())).findFirst().orElse(null);
        if (project == null) throw new IllegalArgumentException("The legacy project was not found.");
        Organization organization = organizations.findOrganization(organizationId);
        if (organization == null || organization.status() != OrganizationStatus.ACTIVE) {
            throw new IllegalStateException("The target organization is not active.");
        }
        ProjectOrganizationOwnership existing = organizations.findProjectOwnership(normalizedProject);
        if (existing != null) throw new IllegalStateException("Project organization ownership is already assigned.");
        Map<String, Object> rootMap = readRoot();
        List<MigrationProposal> proposals = proposals(rootMap);
        MigrationProposal duplicate = proposals.stream()
                .filter(item -> normalizedProject.equals(item.projectId()) && item.status() == ProposalStatus.PENDING)
                .findFirst().orElse(null);
        if (duplicate != null) throw new IllegalStateException("A migration proposal already exists for this project.");
        organizations.quarantineProject(normalizedProject);
        Instant now = Instant.now();
        MigrationProposal proposal = new MigrationProposal(UUID.randomUUID().toString(), normalizedProject,
                organizationId, actorUserId, normalizedSource, normalizedReason, ProposalStatus.PENDING, now, null);
        proposals.add(proposal);
        rootMap.put("proposals", proposals.stream().map(LegacyMigrationReviewService::proposalMap).toList());
        List<MigrationAudit> audit = audits(rootMap);
        audit.add(new MigrationAudit("PROPOSAL_CREATED", proposal.proposalId(), normalizedProject,
                organizationId.toString(), actorUserId, normalizedSource, normalizedReason, "", now));
        rootMap.put("audit", audit.stream().map(LegacyMigrationReviewService::auditMap).toList());
        writeRoot(rootMap);
        return proposal;
    }

    synchronized MigrationProposal approve(String actorUserId, String proposalId) throws IOException {
        jvmLock.lock();
        try { return approveLocked(actorUserId, proposalId); }
        finally { jvmLock.unlock(); }
    }

    private MigrationProposal approveLocked(String actorUserId, String proposalId) throws IOException {
        requireMigrationAdmin(actorUserId);
        String id = required(proposalId, "proposalId");
        Map<String, Object> rootMap = readRoot();
        List<MigrationProposal> proposals = proposals(rootMap);
        MigrationProposal proposal = proposals.stream().filter(item -> id.equals(item.proposalId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Migration proposal was not found."));
        if (proposal.status() == ProposalStatus.APPROVED) return proposal;
        if (proposal.status() != ProposalStatus.PENDING) throw new IllegalStateException("Migration proposal is not pending.");
        Organization target = organizations.findOrganization(proposal.organizationId());
        if (target == null || target.status() != OrganizationStatus.ACTIVE) throw new IllegalStateException("The target organization is not active.");
        if (organizations.findProjectOwnership(proposal.projectId()) != null) throw new IllegalStateException("Project organization ownership is already assigned.");

        String planId = UUID.randomUUID().toString();
        BackupHandle backup;
        try {
            backup = backups.create(planId);
        } catch (IOException backupFailure) {
            appendAudit(rootMap, new MigrationAudit("BACKUP_FAILED", proposal.proposalId(), proposal.projectId(),
                    proposal.organizationId().toString(), actorUserId, proposal.source(), "Migration aborted because backup verification failed.", planId, Instant.now()));
            throw backupFailure;
        }
        appendAudit(rootMap, new MigrationAudit("BACKUP_VERIFIED", proposal.proposalId(), proposal.projectId(),
                proposal.organizationId().toString(), actorUserId, proposal.source(), proposal.reason(), planId, Instant.now()));
        try {
            organizations.assignProject(proposal.projectId(), proposal.organizationId(), proposal.source());
            backups.markPostMutation(backup);
            failureHook.afterAssignment();
            MigrationProposal approved = new MigrationProposal(proposal.proposalId(), proposal.projectId(), proposal.organizationId(),
                    proposal.actorUserId(), proposal.source(), proposal.reason(), ProposalStatus.APPROVED, proposal.createdAt(), Instant.now());
            replaceProposal(rootMap, approved);
            appendAudit(rootMap, new MigrationAudit("ASSIGNMENT_APPROVED", proposal.proposalId(), proposal.projectId(),
                    proposal.organizationId().toString(), actorUserId, proposal.source(), proposal.reason(), planId, Instant.now()));
            return approved;
        } catch (Exception failure) {
            try {
                backups.restore(backup);
                MigrationProposal recovered = new MigrationProposal(proposal.proposalId(), proposal.projectId(), proposal.organizationId(),
                        proposal.actorUserId(), proposal.source(), proposal.reason(), ProposalStatus.RECOVERED, proposal.createdAt(), null);
                replaceProposal(rootMap, recovered);
                appendAudit(rootMap, new MigrationAudit("ASSIGNMENT_RECOVERED", proposal.proposalId(), proposal.projectId(),
                        proposal.organizationId().toString(), actorUserId, proposal.source(), "Assignment failure compensated safely.", planId, Instant.now()));
            } catch (Exception recoveryFailure) {
                appendAudit(rootMap, new MigrationAudit("RECOVERY_FAILED", proposal.proposalId(), proposal.projectId(),
                        proposal.organizationId().toString(), actorUserId, proposal.source(), "Manual recovery required.", planId, Instant.now()));
                writeRoot(rootMap);
                throw new IllegalStateException("Migration failed and requires manual recovery review.", recoveryFailure);
            }
            writeRoot(rootMap);
            throw new IllegalStateException("Migration failed; the assignment was compensated.", failure);
        }
    }

    synchronized List<MigrationAudit> audit(String actorUserId) throws IOException {
        requireMigrationAdmin(actorUserId);
        return audits(readRoot());
    }

    synchronized List<MigrationProposal> proposals(String actorUserId) throws IOException {
        requireMigrationAdmin(actorUserId);
        return proposals(readRoot());
    }

    private void requireMigrationAdmin(String actorUserId) throws IOException {
        AuthUserDirectory.User user = new AuthUserDirectory(root).findById(actorUserId);
        if (user == null || !AuthUserDirectory.ACTIVE.equals(user.status())) throw TenantAuthorizationException.unauthenticated();
        TeamMemberManager.Member member = teamMembers.list().stream()
                .filter(item -> actorUserId.equals(item.registeredUserId()) && TeamMemberManager.ACTIVE.equals(item.status()))
                .findFirst().orElse(null);
        if (member == null || !"Admin".equals(member.role())) throw TenantAuthorizationException.forbidden();
    }

    private void replaceProposal(Map<String, Object> rootMap, MigrationProposal proposal) throws IOException {
        List<MigrationProposal> values = proposals(rootMap);
        values.replaceAll(item -> item.proposalId().equals(proposal.proposalId()) ? proposal : item);
        rootMap.put("proposals", values.stream().map(LegacyMigrationReviewService::proposalMap).toList());
        writeRoot(rootMap);
    }

    private void appendAudit(Map<String, Object> rootMap, MigrationAudit event) throws IOException {
        List<MigrationAudit> values = audits(rootMap);
        values.add(event);
        rootMap.put("audit", values.stream().map(LegacyMigrationReviewService::auditMap).toList());
        writeRoot(rootMap);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot() throws IOException {
        return withLock(() -> {
            if (!Files.exists(reviewPath)) return new LinkedHashMap<>();
            Object parsed = JsonParser.parse(Files.readString(reviewPath));
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Migration review store is not a JSON object.");
            Object version = map.get("schemaVersion");
            if (version == null) version = CURRENT_SCHEMA_VERSION;
            if (!(version instanceof Number number) || number.intValue() > CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported migration review schema version.");
            return new LinkedHashMap<>((Map<String, Object>) map);
        });
    }

    private void writeRoot(Map<String, Object> rootMap) throws IOException {
        withLock(() -> {
            rootMap.put("schemaVersion", CURRENT_SCHEMA_VERSION);
            Path temporary = reviewPath.resolveSibling(reviewPath.getFileName() + ".tmp");
            Files.writeString(temporary, CiCdConfig.toJson(rootMap), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try { Files.move(temporary, reviewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, reviewPath, StandardCopyOption.REPLACE_EXISTING); }
            return null;
        });
    }

    private <T> T withLock(IoSupplier<T> action) throws IOException {
        jvmLock.lock();
        try { return action.get(); } finally { jvmLock.unlock(); }
    }

    private interface IoSupplier<T> { T get() throws IOException; }

    @SuppressWarnings("unchecked")
    private static List<MigrationProposal> proposals(Map<String, Object> root) {
        Object raw = root.get("proposals");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        return list.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> map = (Map<String, Object>) item;
            return new MigrationProposal(String.valueOf(map.get("proposalId")), String.valueOf(map.get("projectId")),
                    UUID.fromString(String.valueOf(map.get("organizationId"))), String.valueOf(map.get("actorUserId")),
                    String.valueOf(map.get("source")), String.valueOf(map.get("reason")),
                    ProposalStatus.valueOf(String.valueOf(map.get("status"))), Instant.parse(String.valueOf(map.get("createdAt"))),
                    map.get("approvedAt") == null ? null : Instant.parse(String.valueOf(map.get("approvedAt"))));
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    @SuppressWarnings("unchecked")
    private static List<MigrationAudit> audits(Map<String, Object> root) {
        Object raw = root.get("audit");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        return list.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> map = (Map<String, Object>) item;
            return new MigrationAudit(String.valueOf(map.get("event")), String.valueOf(map.get("proposalId")),
                    String.valueOf(map.get("projectId")), String.valueOf(map.get("organizationId")),
                    String.valueOf(map.get("actorUserId")), String.valueOf(map.get("source")), String.valueOf(map.get("reason")),
                    String.valueOf(map.getOrDefault("backupPlanId", "")), Instant.parse(String.valueOf(map.get("timestamp"))));
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Map<String, Object> proposalMap(MigrationProposal value) {
        return map("proposalId", value.proposalId(), "projectId", value.projectId(), "organizationId", value.organizationId().toString(),
                "actorUserId", value.actorUserId(), "source", value.source(), "reason", value.reason(), "status", value.status().name(),
                "createdAt", value.createdAt().toString(), "approvedAt", value.approvedAt() == null ? null : value.approvedAt().toString());
    }

    private static Map<String, Object> auditMap(MigrationAudit value) {
        return map("event", value.event(), "proposalId", value.proposalId(), "projectId", value.projectId(),
                "organizationId", value.organizationId(), "actorUserId", value.actorUserId(), "source", value.source(),
                "reason", value.reason(), "backupPlanId", value.backupPlanId(), "timestamp", value.timestamp().toString());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 400) throw new IllegalArgumentException(name + " is required.");
        return value.trim();
    }

    private static final class FileBackupCoordinator implements BackupCoordinator {
        private final Path root;
        private final OrganizationStore organizations;
        private final Path backupRoot;

        FileBackupCoordinator(Path root, OrganizationStore organizations) {
            this.root = root.toAbsolutePath().normalize();
            this.organizations = organizations;
            this.backupRoot = this.root.resolve(".qa-migration-backups");
        }

        @Override
        public BackupHandle create(String planId) throws IOException {
            Path directory = backupRoot.resolve(planId).normalize();
            if (!directory.startsWith(backupRoot) || Files.exists(directory)) throw new IOException("Backup plan path is invalid or already exists.");
            Files.createDirectories(directory);
            List<Path> sources = List.of(root.resolve("qa-runner.json"), organizations.dataPath());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Path source : sources) {
                Path normalized = source.toAbsolutePath().normalize();
                if (!normalized.startsWith(root)) throw new IOException("Backup source escaped the project root.");
                boolean exists = Files.isRegularFile(normalized);
                String fileName = normalized.getFileName().toString();
                String hash = exists ? hashAndValidateJson(normalized) : "";
                if (exists) Files.copy(normalized, directory.resolve(fileName), StandardCopyOption.COPY_ATTRIBUTES);
                entries.add(map("file", fileName, "exists", exists, "preHash", hash, "postHash", ""));
            }
            Path manifest = directory.resolve("manifest.json");
            Files.writeString(manifest, CiCdConfig.toJson(map("schemaVersion", 1, "planId", planId,
                    "createdAt", Instant.now().toString(), "entries", entries)), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!Files.isRegularFile(manifest) || !JsonParser.parse(Files.readString(manifest)).toString().contains(planId)) throw new IOException("Backup manifest verification failed.");
            return new BackupHandle(planId, manifest);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void markPostMutation(BackupHandle handle) throws IOException {
            Map<String, Object> manifest = readManifest(handle);
            List<Object> entries = (List<Object>) manifest.get("entries");
            for (Object raw : entries) {
                Map<String, Object> entry = (Map<String, Object>) raw;
                Path current = root.resolve(String.valueOf(entry.get("file"))).normalize();
                entry.put("postHash", Files.isRegularFile(current) ? sha256(current) : "");
            }
            Files.writeString(handle.manifestPath(), CiCdConfig.toJson(manifest), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(BackupHandle handle) throws IOException {
            Map<String, Object> manifest = readManifest(handle);
            List<Object> entries = (List<Object>) manifest.get("entries");
            Path directory = handle.manifestPath().getParent();
            for (Object raw : entries) {
                Map<String, Object> entry = (Map<String, Object>) raw;
                Path target = root.resolve(String.valueOf(entry.get("file"))).normalize();
                if (!target.startsWith(root)) throw new IOException("Recovery target escaped the project root.");
                String postHash = String.valueOf(entry.getOrDefault("postHash", ""));
                if (postHash.isBlank()) throw new IOException("Recovery refused because post-mutation integrity is unknown.");
                String currentHash = Files.isRegularFile(target) ? sha256(target) : "";
                if (!postHash.equals(currentHash)) throw new IOException("Recovery refused because newer data is present.");
                boolean existed = Boolean.TRUE.equals(entry.get("exists"));
                if (existed) {
                    Path backup = directory.resolve(String.valueOf(entry.get("file"))).normalize();
                    if (!backup.startsWith(directory) || !Files.isRegularFile(backup)) throw new IOException("Verified backup file is missing.");
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } else Files.deleteIfExists(target);
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readManifest(BackupHandle handle) throws IOException {
            if (handle == null || !Files.isRegularFile(handle.manifestPath())) throw new IOException("Backup manifest is unavailable.");
            Object parsed = JsonParser.parse(Files.readString(handle.manifestPath()));
            if (!(parsed instanceof Map<?, ?> map) || !handle.planId().equals(String.valueOf(map.get("planId")))) throw new IOException("Backup manifest is invalid.");
            return new LinkedHashMap<>((Map<String, Object>) map);
        }

        private static String hashAndValidateJson(Path path) throws IOException {
            Object parsed = JsonParser.parse(Files.readString(path));
            if (!(parsed instanceof Map<?, ?>)) throw new IOException("Affected JSON store is not readable.");
            return sha256(path);
        }

        private static String sha256(Path path) throws IOException {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                StringBuilder result = new StringBuilder();
                for (byte value : digest) result.append(String.format("%02x", value));
                return result.toString();
            } catch (Exception error) { throw new IOException("Could not hash backup data.", error); }
        }
    }
}
