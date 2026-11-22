package com.example.unifiedqarunner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Versioned organization-only persistence. It intentionally does not mutate
 * qa-runner.json: legacy project data remains outside the Level 4.1 store
 * until an explicit, reviewed migration is implemented.
 */
final class OrganizationStore {
    static final int CURRENT_SCHEMA_VERSION = 1;
    static final String FILE_NAME = ".qa-organizations.json";
    private static final String VERSION = "organizationSchemaVersion";
    private static final String ORGANIZATIONS = "organizations";
    private static final String MEMBERSHIPS = "organizationMemberships";
    private static final String INVITATIONS = "organizationInvitations";
    private static final String PROJECTS = "projectOrganizationOwnership";
    private static final String QUARANTINED = "quarantinedProjectIds";
    private static final Set<String> KNOWN_KEYS = Set.of(VERSION, ORGANIZATIONS, MEMBERSHIPS,
            INVITATIONS, PROJECTS, QUARANTINED);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path root;
    private final Path dataPath;
    private final Path backupPath;
    private final Path lockPath;
    private final ReentrantLock jvmLock;

    OrganizationStore(Path projectRoot) {
        root = projectRoot.toAbsolutePath().normalize();
        dataPath = root.resolve(FILE_NAME);
        backupPath = root.resolve(FILE_NAME + ".bak");
        lockPath = root.resolve(FILE_NAME + ".lock");
        jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        StorageWriteCoordinator.registerPersistentWriter(root, "organizations");
    }

    Path dataPath() { return dataPath; }
    Path backupPath() { return backupPath; }

    List<Organization> organizations() throws IOException {
        return read(rootMap -> list(rootMap, ORGANIZATIONS, this::organization));
    }

    Organization findOrganization(UUID id) throws IOException {
        if (id == null) return null;
        return organizations().stream().filter(item -> id.equals(item.organizationId())).findFirst().orElse(null);
    }

    Organization createOrganization(String name, String slug, String ownerUserId) throws IOException {
        Instant now = Instant.now();
        Organization organization = new Organization(UUID.randomUUID(), name, slug, ownerUserId,
                OrganizationStatus.ACTIVE, now, now, CURRENT_SCHEMA_VERSION);
        return write(root -> {
            List<Organization> organizations = list(root, ORGANIZATIONS, this::organization);
            if (organizations.stream().anyMatch(item -> item.organizationSlug().equals(organization.organizationSlug()))) {
                throw new IllegalArgumentException("An organization with this slug already exists.");
            }
            organizations.add(organization);
            put(root, ORGANIZATIONS, organizations.stream().map(this::organizationMap).toList());
            List<OrganizationMembership> memberships = list(root, MEMBERSHIPS, this::membership);
            memberships.add(new OrganizationMembership(UUID.randomUUID(), organization.organizationId(), ownerUserId,
                    OrganizationRole.OWNER, MembershipStatus.ACTIVE, now, now));
            put(root, MEMBERSHIPS, memberships.stream().map(this::membershipMap).toList());
            return organization;
        });
    }

    Organization updateOrganization(UUID id, String name, String slug, OrganizationStatus status) throws IOException {
        OrganizationValidation.requiredText(id == null ? "" : id.toString(), "organizationId", 80);
        String normalizedSlug = OrganizationValidation.slug(slug);
        return write(root -> {
            List<Organization> organizations = list(root, ORGANIZATIONS, this::organization);
            Organization current = organizations.stream().filter(item -> id.equals(item.organizationId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Organization was not found."));
            if (organizations.stream().anyMatch(item -> !id.equals(item.organizationId())
                    && item.organizationSlug().equals(normalizedSlug))) {
                throw new IllegalArgumentException("An organization with this slug already exists.");
            }
            Organization updated = new Organization(current.organizationId(), name, normalizedSlug,
                    current.ownerUserId(), status, current.createdAt(), Instant.now(), CURRENT_SCHEMA_VERSION);
            organizations.replaceAll(item -> id.equals(item.organizationId()) ? updated : item);
            put(root, ORGANIZATIONS, organizations.stream().map(this::organizationMap).toList());
            return updated;
        });
    }

    List<OrganizationMembership> memberships(UUID organizationId) throws IOException {
        return read(root -> list(root, MEMBERSHIPS, this::membership).stream()
                .filter(item -> organizationId == null || organizationId.equals(item.organizationId())).toList());
    }

    OrganizationMembership findMembership(UUID organizationId, String userId) throws IOException {
        if (organizationId == null || userId == null || userId.isBlank()) return null;
        return memberships(organizationId).stream().filter(item -> userId.equals(item.userId())).findFirst().orElse(null);
    }

    OrganizationMembership createMembership(OrganizationMembership candidate) throws IOException {
        return write(root -> {
            Organization organization = requireOrganization(root, candidate.organizationId());
            List<OrganizationMembership> memberships = list(root, MEMBERSHIPS, this::membership);
            if (memberships.stream().anyMatch(item -> item.organizationId().equals(candidate.organizationId())
                    && item.userId().equals(candidate.userId()))) {
                throw new IllegalArgumentException("The user already has membership in this organization.");
            }
            if (candidate.organizationRole() == OrganizationRole.OWNER
                    && !organization.ownerUserId().equals(candidate.userId())) {
                throw new IllegalArgumentException("Only the organization owner may hold the owner role.");
            }
            memberships.add(candidate);
            put(root, MEMBERSHIPS, memberships.stream().map(this::membershipMap).toList());
            return candidate;
        });
    }

    OrganizationMembership updateMembership(UUID membershipId, OrganizationRole role,
                                             MembershipStatus status) throws IOException {
        return write(root -> {
            List<OrganizationMembership> memberships = list(root, MEMBERSHIPS, this::membership);
            OrganizationMembership current = memberships.stream().filter(item -> membershipId.equals(item.membershipId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Membership was not found."));
            Organization organization = requireOrganization(root, current.organizationId());
            if (role == OrganizationRole.OWNER && !organization.ownerUserId().equals(current.userId())) {
                throw new IllegalArgumentException("Only the organization owner may hold the owner role.");
            }
            if (current.organizationRole() == OrganizationRole.OWNER
                    && (role != OrganizationRole.OWNER || status != MembershipStatus.ACTIVE)
                    && memberships.stream().noneMatch(item -> !item.membershipId().equals(membershipId)
                    && item.organizationId().equals(current.organizationId())
                    && item.organizationRole() == OrganizationRole.OWNER
                    && item.membershipStatus() == MembershipStatus.ACTIVE)) {
                throw new IllegalStateException("The last active organization owner cannot be removed or suspended.");
            }
            OrganizationMembership updated = new OrganizationMembership(current.membershipId(), current.organizationId(),
                    current.userId(), role, status, current.createdAt(), Instant.now());
            memberships.replaceAll(item -> membershipId.equals(item.membershipId()) ? updated : item);
            put(root, MEMBERSHIPS, memberships.stream().map(this::membershipMap).toList());
            return updated;
        });
    }

    OrganizationInvitation createInvitation(UUID organizationId, String invitedEmail, String invitedUserId,
                                             OrganizationRole intendedRole, String rawToken,
                                             Instant expiresAt, String invitedBy) throws IOException {
        Instant now = Instant.now();
        String hash = OrganizationValidation.hashToken(rawToken);
        OrganizationInvitation candidate = new OrganizationInvitation(UUID.randomUUID(), organizationId,
                invitedEmail, invitedUserId, intendedRole, hash, expiresAt, invitedBy,
                InvitationStatus.PENDING, now, null);
        return write(root -> {
            requireOrganization(root, organizationId);
            requireActiveManager(root, organizationId, invitedBy);
            List<OrganizationInvitation> invitations = list(root, INVITATIONS, this::invitation);
            if (invitations.stream().anyMatch(item -> item.organizationId().equals(organizationId)
                    && item.invitedEmail().equals(candidate.invitedEmail()) && item.status() == InvitationStatus.PENDING)) {
                throw new IllegalArgumentException("A pending invitation already exists for this identity.");
            }
            invitations.add(candidate);
            put(root, INVITATIONS, invitations.stream().map(this::invitationMap).toList());
            return candidate;
        });
    }

    /** Returns invitation metadata for authorized callers; callers must never serialize tokenHash. */
    List<OrganizationInvitation> invitations(UUID organizationId) throws IOException {
        return read(root -> list(root, INVITATIONS, this::invitation).stream()
                .filter(item -> organizationId == null || organizationId.equals(item.organizationId())).toList());
    }

    OrganizationMembership acceptInvitation(UUID invitationId, String rawToken, String verifiedUserId,
                                            String verifiedEmail, Instant now) throws IOException {
        String userId = OrganizationValidation.userId(verifiedUserId, "verifiedUserId");
        String email = OrganizationValidation.email(verifiedEmail);
        return write(root -> {
            List<OrganizationInvitation> invitations = list(root, INVITATIONS, this::invitation);
            OrganizationInvitation invitation = invitations.stream().filter(item -> invitationId.equals(item.invitationId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Invitation was not found."));
            if (invitation.status() != InvitationStatus.PENDING) throw new IllegalStateException("Invitation is no longer usable.");
            if (!invitation.expiresAt().isAfter(now)) throw new IllegalStateException("Invitation has expired.");
            if (!invitation.tokenHash().equals(OrganizationValidation.hashToken(rawToken))) throw new IllegalArgumentException("Invitation token is invalid.");
            if (!invitation.invitedEmail().equals(email)) throw new IllegalArgumentException("Invitation identity does not match.");
            if (!invitation.invitedUserId().isBlank() && !invitation.invitedUserId().equals(userId)) {
                throw new IllegalArgumentException("Invitation identity does not match.");
            }
            List<OrganizationMembership> memberships = list(root, MEMBERSHIPS, this::membership);
            if (memberships.stream().anyMatch(item -> item.organizationId().equals(invitation.organizationId()) && item.userId().equals(userId))) {
                throw new IllegalStateException("The user already has membership in this organization.");
            }
            OrganizationMembership membership = new OrganizationMembership(UUID.randomUUID(), invitation.organizationId(), userId,
                    invitation.intendedRole(), MembershipStatus.ACTIVE, now, now);
            memberships.add(membership);
            put(root, MEMBERSHIPS, memberships.stream().map(this::membershipMap).toList());
            OrganizationInvitation accepted = new OrganizationInvitation(invitation.invitationId(), invitation.organizationId(),
                    invitation.invitedEmail(), invitation.invitedUserId(), invitation.intendedRole(), invitation.tokenHash(),
                    invitation.expiresAt(), invitation.invitedBy(), InvitationStatus.ACCEPTED, invitation.createdAt(), now);
            invitations.replaceAll(item -> invitationId.equals(item.invitationId()) ? accepted : item);
            put(root, INVITATIONS, invitations.stream().map(this::invitationMap).toList());
            return membership;
        });
    }

    ProjectOrganizationOwnership assignProject(String projectId, UUID organizationId, String provenance) throws IOException {
        ProjectOrganizationOwnership candidate = new ProjectOrganizationOwnership(projectId, organizationId, provenance, Instant.now());
        return write(root -> {
            requireOrganization(root, organizationId);
            List<ProjectOrganizationOwnership> assignments = list(root, PROJECTS, this::projectOwnership);
            ProjectOrganizationOwnership existing = assignments.stream().filter(item -> item.projectId().equals(candidate.projectId())).findFirst().orElse(null);
            if (existing != null && !existing.organizationId().equals(organizationId)) {
                throw new IllegalStateException("Project organization ownership is immutable once assigned.");
            }
            if (existing != null) return existing;
            assignments.add(candidate);
            put(root, PROJECTS, assignments.stream().map(this::projectOwnershipMap).toList());
            List<String> quarantined = quarantine(root);
            quarantined.remove(candidate.projectId());
            put(root, QUARANTINED, quarantined);
            return candidate;
        });
    }

    ProjectOrganizationOwnership findProjectOwnership(String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) return null;
        return read(root -> list(root, PROJECTS, this::projectOwnership).stream()
                .filter(item -> projectId.equals(item.projectId())).findFirst().orElse(null));
    }

    List<ProjectOrganizationOwnership> projectOwnerships() throws IOException {
        return read(root -> list(root, PROJECTS, this::projectOwnership));
    }

    void quarantineProject(String projectId) throws IOException {
        String normalized = OrganizationValidation.requiredText(projectId, "projectId", 200);
        write(root -> {
            List<ProjectOrganizationOwnership> assignments = list(root, PROJECTS, this::projectOwnership);
            if (assignments.stream().noneMatch(item -> item.projectId().equals(normalized))) {
                List<String> quarantined = quarantine(root);
                if (!quarantined.contains(normalized)) quarantined.add(normalized);
                put(root, QUARANTINED, quarantined);
            }
            return null;
        });
    }

    List<String> quarantinedProjectIds() throws IOException {
        return read(root -> List.copyOf(quarantine(root)));
    }

    void restoreBackup() throws IOException {
        withFileLock(() -> {
            Map<String, Object> backup = readRoot(backupPath);
            validateVersion(backup);
            Files.createDirectories(root);
            Path temp = dataPath.resolveSibling(dataPath.getFileName() + ".restore.tmp");
            Files.writeString(temp, CiCdConfig.toJson(backup), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveReplace(temp, dataPath);
            return null;
        });
    }

    private <T> T read(Function<Map<String, Object>, T> action) throws IOException {
        return withFileLock(() -> {
            Map<String, Object> rootMap = readRoot(dataPath);
            validateVersion(rootMap);
            return action.apply(rootMap);
        });
    }

    private <T> T write(Function<Map<String, Object>, T> mutation) throws IOException {
        return StorageWriteCoordinator.coordinatedCall(root, "organizations", () -> withFileLock(() -> {
            Map<String, Object> rootMap = readRoot(dataPath);
            validateVersion(rootMap);
            T result = mutation.apply(rootMap);
            rootMap.put(VERSION, CURRENT_SCHEMA_VERSION);
            writeRoot(rootMap);
            return result;
        }));
    }

    private <T> T withFileLock(IoSupplier<T> action) throws IOException {
        jvmLock.lock();
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private Map<String, Object> readRoot(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            Object parsed = JsonParser.parse(Files.readString(path));
            if (!(parsed instanceof Map<?, ?> map)) throw new OrganizationPersistenceException("Organization store must contain a JSON object.");
            @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) map;
            return new LinkedHashMap<>(cast);
        } catch (OrganizationPersistenceException error) {
            throw error;
        } catch (Exception error) {
            throw new OrganizationPersistenceException("Organization store is corrupt and was not overwritten.", error);
        }
    }

    private void validateVersion(Map<String, Object> rootMap) {
        int version = number(rootMap.getOrDefault(VERSION, 0), VERSION);
        if (version > CURRENT_SCHEMA_VERSION) throw new OrganizationPersistenceException("Unsupported future organization schema version: " + version);
        if (version < 0) throw new OrganizationPersistenceException("Invalid organization schema version: " + version);
    }

    private void writeRoot(Map<String, Object> rootMap) throws IOException {
        Files.createDirectories(root);
        Path temp = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
        try {
            Files.writeString(temp, CiCdConfig.toJson(rootMap), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (Files.exists(dataPath)) Files.copy(dataPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temp, dataPath);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private Organization requireOrganization(Map<String, Object> rootMap, UUID id) {
        return list(rootMap, ORGANIZATIONS, this::organization).stream().filter(item -> id.equals(item.organizationId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Organization was not found."));
    }

    private void requireActiveManager(Map<String, Object> rootMap, UUID organizationId, String userId) {
        boolean allowed = list(rootMap, MEMBERSHIPS, this::membership).stream().anyMatch(item -> item.organizationId().equals(organizationId)
                && item.userId().equals(userId) && item.membershipStatus() == MembershipStatus.ACTIVE
                && (item.organizationRole() == OrganizationRole.OWNER || item.organizationRole() == OrganizationRole.ADMIN));
        if (!allowed) throw new IllegalArgumentException("An active organization owner or admin is required.");
    }

    private List<String> quarantine(Map<String, Object> rootMap) {
        Object raw = rootMap.get(QUARANTINED);
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> values) for (Object value : values) if (value != null) result.add(String.valueOf(value));
        return result;
    }

    private <T> List<T> list(Map<String, Object> rootMap, String key, Function<Map<String, Object>, T> mapper) {
        Object raw = rootMap.get(key);
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> values)) throw new OrganizationPersistenceException("Organization store field '" + key + "' must be an array.");
        List<T> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) throw new OrganizationPersistenceException("Organization store field '" + key + "' contains an invalid record.");
            @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) map;
            result.add(mapper.apply(cast));
        }
        return result;
    }

    private void put(Map<String, Object> rootMap, String key, Object value) { rootMap.put(key, value); }

    private Organization organization(Map<String, Object> map) {
        return new Organization(uuid(map, "organizationId"), text(map, "organizationName"), text(map, "organizationSlug"),
                text(map, "ownerUserId"), enumValue(map, "status", OrganizationStatus.class), instant(map, "createdAt"),
                instant(map, "updatedAt"), number(map.getOrDefault("schemaVersion", CURRENT_SCHEMA_VERSION), "schemaVersion"));
    }

    private OrganizationMembership membership(Map<String, Object> map) {
        return new OrganizationMembership(uuid(map, "membershipId"), uuid(map, "organizationId"), text(map, "userId"),
                enumValue(map, "organizationRole", OrganizationRole.class), enumValue(map, "membershipStatus", MembershipStatus.class),
                instant(map, "createdAt"), instant(map, "updatedAt"));
    }

    private OrganizationInvitation invitation(Map<String, Object> map) {
        Object accepted = map.get("acceptedAt");
        return new OrganizationInvitation(uuid(map, "invitationId"), uuid(map, "organizationId"), text(map, "invitedEmail"),
                String.valueOf(map.getOrDefault("invitedUserId", "")), enumValue(map, "intendedRole", OrganizationRole.class),
                text(map, "tokenHash"), instant(map, "expiresAt"), text(map, "invitedBy"),
                enumValue(map, "status", InvitationStatus.class), instant(map, "createdAt"), accepted == null ? null : instantValue(accepted, "acceptedAt"));
    }

    private ProjectOrganizationOwnership projectOwnership(Map<String, Object> map) {
        return new ProjectOrganizationOwnership(text(map, "projectId"), uuid(map, "organizationId"),
                text(map, "assignmentProvenance"), instant(map, "assignedAt"));
    }

    private Map<String, Object> organizationMap(Organization value) {
        return map("organizationId", value.organizationId().toString(), "organizationName", value.organizationName(),
                "organizationSlug", value.organizationSlug(), "ownerUserId", value.ownerUserId(), "status", value.status().name(),
                "createdAt", value.createdAt().toString(), "updatedAt", value.updatedAt().toString(), "schemaVersion", value.schemaVersion());
    }

    private Map<String, Object> membershipMap(OrganizationMembership value) {
        return map("membershipId", value.membershipId().toString(), "organizationId", value.organizationId().toString(),
                "userId", value.userId(), "organizationRole", value.organizationRole().name(), "membershipStatus", value.membershipStatus().name(),
                "createdAt", value.createdAt().toString(), "updatedAt", value.updatedAt().toString());
    }

    private Map<String, Object> invitationMap(OrganizationInvitation value) {
        Map<String, Object> map = map("invitationId", value.invitationId().toString(), "organizationId", value.organizationId().toString(),
                "invitedEmail", value.invitedEmail(), "invitedUserId", value.invitedUserId(), "intendedRole", value.intendedRole().name(),
                "tokenHash", value.tokenHash(), "expiresAt", value.expiresAt().toString(), "invitedBy", value.invitedBy(),
                "status", value.status().name(), "createdAt", value.createdAt().toString());
        map.put("acceptedAt", value.acceptedAt() == null ? null : value.acceptedAt().toString());
        return map;
    }

    private Map<String, Object> projectOwnershipMap(ProjectOrganizationOwnership value) {
        return map("projectId", value.projectId(), "organizationId", value.organizationId().toString(),
                "assignmentProvenance", value.assignmentProvenance(), "assignedAt", value.assignedAt().toString());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) throw new OrganizationPersistenceException("Organization record is missing " + key + ".");
        return String.valueOf(value);
    }

    private static UUID uuid(Map<String, Object> map, String key) {
        try { return UUID.fromString(text(map, key)); }
        catch (IllegalArgumentException error) { throw new OrganizationPersistenceException("Organization record contains an invalid " + key + ".", error); }
    }

    private static Instant instant(Map<String, Object> map, String key) { return instantValue(map.get(key), key); }

    private static Instant instantValue(Object value, String key) {
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception error) { throw new OrganizationPersistenceException("Organization record contains an invalid " + key + ".", error); }
    }

    private static int number(Object value, String key) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception error) { throw new OrganizationPersistenceException("Organization store contains an invalid " + key + ".", error); }
    }

    private static <E extends Enum<E>> E enumValue(Map<String, Object> map, String key, Class<E> type) {
        try { return Enum.valueOf(type, text(map, key)); }
        catch (Exception error) { throw new OrganizationPersistenceException("Organization record contains an invalid " + key + ".", error); }
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws IOException; }
}
