# Level 4.1.2 Organization Domain Model and Versioned Persistence

Date: 2026-11-13  
Status: **PASS**

## 1. Files created/modified

Created:

- `Organization.java`
- `OrganizationMembership.java`
- `OrganizationInvitation.java`
- `ProjectOrganizationOwnership.java`
- `OrganizationStatus.java`
- `OrganizationRole.java`
- `MembershipStatus.java`
- `InvitationStatus.java`
- `OrganizationValidation.java`
- `OrganizationPersistenceException.java`
- `OrganizationStore.java`
- `OrganizationStoreTest.java`

Modified: no existing authentication, authorization, frontend, project, or running-application files.

## 2. Organization model design

- `Organization` uses an immutable UUID, normalized unique slug, owner user ID, lifecycle status, UTC `Instant` timestamps, and schema version.
- `OrganizationMembership` uses an immutable membership UUID, organization UUID, UUID-form user ID, organization role, membership status, and UTC timestamps.
- `OrganizationInvitation` stores organization and inviter references, normalized invited email, optional verified invited user ID, permitted intended role, SHA-256 token hash, expiry, status, and acceptance timestamp. Raw tokens are never persisted.
- `ProjectOrganizationOwnership` maps one existing project ID to one immutable organization UUID with provenance and assignment timestamp.
- Organization owner membership is created atomically with explicit organization creation. The owner role cannot be assigned to another identity.
- The last active owner cannot be demoted or suspended.

## 3. Persistence schema

New dedicated file: `.qa-organizations.json`  
Current schema: `organizationSchemaVersion: 1`

Top-level sections:

- `organizations`
- `organizationMemberships`
- `organizationInvitations`
- `projectOrganizationOwnership`
- `quarantinedProjectIds`

The existing `qa-runner.json` file is not rewritten or migrated in this phase. Unknown fields in the dedicated organization file are preserved. Missing/older schema versions are accepted and upgraded only on a successful organization-store write. Future schema versions are rejected without overwrite.

## 4. Data integrity safeguards

- Organization slugs are normalized and unique.
- Memberships are unique by organization/user pair.
- Invitation creation rejects duplicate pending invitations.
- Invitations are cryptographically hashed, expiry checked, identity-bound, and single-use.
- Project organization assignment is immutable after first assignment.
- Legacy/unassigned project IDs can be explicitly quarantined and are never automatically assigned.
- Writes use a JVM lock plus an OS lock file, temporary-file serialization, atomic replacement when supported, and a `.bak` copy before replacement.
- Corrupt JSON and unsupported future schemas fail closed and are not overwritten.
- Backup restoration validates the backup schema before replacement.
- No public organization endpoints or tenant authorization bypasses were added.

## 5. Test results

Final command:

```text
mvn -q -Dtest=OrganizationStoreTest,AuthenticationFoundationTest,AuthVerificationManagerTest,TeamAuthorizationTest,TeamMemberManagerTest,LocalTwoAccountSecurityHarnessTest,ProjectOwnershipManagerTest,SharedPathConfigTest,LiveTwoAccountAuthorizationTest -Dqa.runner.liveBrowser=true test
```

Result: exit code `0`.

- Organization persistence/model tests: 8 passed
- Authentication foundation: 4 passed
- Verification manager: 2 passed
- Team authorization: 6 passed
- Team members: 3 passed
- Local security harness: 3 passed
- Project ownership: 2 passed
- Shared paths: 1 passed
- Live four-role browser regression: 1 passed
- Total: **30 passed, 0 failed, 0 skipped**
- Maven compile: passed
- `git diff --check`: passed

All tests use temporary directories. The current application on port 8081 was not restarted or modified.

## 6. Legacy compatibility

- Existing `qa-runner.json` data remains untouched.
- Existing project ownership and unresolved-resource quarantine remain unchanged.
- No legacy project was assigned to an organization.
- Unknown fields in the new organization store are preserved.
- Corrupt and future-version files are rejected without destructive recovery.

## 7. Remaining risks

1. Tenant context resolution and organization authorization are intentionally not implemented until the next Level 4.1 phase.
2. The store validates user ID shape, but live existence checks against `AuthUserDirectory` belong in the future organization service/API boundary.
3. The backup and restore primitive exists, but no administrator-facing migration review workflow exists yet.
4. Resource-by-resource tenant enforcement and organization frontend switching remain out of scope for this phase.

## 8. Level 4.1.2 exit

**PASS.** The organization domain model and isolated, versioned JSON persistence are implemented and validated without changing existing user data, authentication behavior, public APIs, frontend behavior, PostgreSQL state, or the running application.

Level 4.2 PostgreSQL migration was not started.
