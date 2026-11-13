# Level 4.1.5 — Safe Legacy Migration and Quarantine

## Result

**Level 4.1.5: PASS.**

The implementation is internal and review-driven. No public migration API or browser UI was added. The application on port 8081, existing user data, and PostgreSQL scope were left untouched.

## Files created or modified

Created:

- [LegacyMigrationReviewService.java](../src/main/java/com/example/unifiedqarunner/LegacyMigrationReviewService.java)
- [LegacyMigrationReviewServiceTest.java](../src/test/java/com/example/unifiedqarunner/LegacyMigrationReviewServiceTest.java)
- [level4.1.5-final-report.md](./level4.1.5-final-report.md)

Modified:

- [OrganizationStore.java](../src/main/java/com/example/unifiedqarunner/OrganizationStore.java) now exposes a read-only project-organization assignment inventory for the review service.

## Legacy inventory findings

The inventory is read-only and platform-migration-admin-only. Each entry reports:

- Project ID and name.
- Active project-membership evidence.
- Resolved resource-to-project evidence from [ProjectOwnershipManager.java](../src/main/java/com/example/unifiedqarunner/ProjectOwnershipManager.java).
- Assignment status.
- Quarantine status.
- Explicit ambiguity reason.

Project names, usernames, emails, and legacy owner display fields are never used as proof of organization ownership. An unassigned project is reported as quarantined because no authoritative organization assignment exists.

## Migration approval architecture

[LegacyMigrationReviewService.java](../src/main/java/com/example/unifiedqarunner/LegacyMigrationReviewService.java) provides internal methods for:

1. Read-only inventory.
2. Explicit target-organization proposal.
3. Active-organization and project-existence validation.
4. Conflict and duplicate-proposal rejection.
5. Explicit platform Admin approval.
6. Sanitized persistent proposal and audit records.

Organization Owner/Admin roles are not accepted as migration administrators unless the identity also has the existing explicit platform-level `Admin` team role. This keeps organization roles separate from platform migration authority.

Review state is stored in versioned `.qa-migration-review.json`. Raw credentials, tokens, and secrets are not stored. Proposal and audit writes use bounded atomic replacement and a shared JVM lock; proposal/approval validation and persistence are serialized across service instances in the JVM.

## Backup and rollback safeguards

Before assignment mutation, the default backup coordinator creates a unique plan directory under `.qa-migration-backups` and snapshots:

- `qa-runner.json`.
- `.qa-organizations.json`.

Each existing JSON store is parsed, hashed, copied, and recorded in a verified manifest. Missing organization storage is represented explicitly as an absent pre-state. Backup failure records `BACKUP_FAILED` and aborts before assignment.

After mutation, post-state hashes are recorded. If a post-assignment failure occurs, compensation restores only when the current files match the recorded post-mutation hashes. Recovery refuses to overwrite newer data and emits a recovery-failure audit event instead. The implementation does not claim cross-file atomic transactions; it uses verified snapshots and compensation.

Retries are idempotent: approved proposals return their existing approval, and immutable project ownership rejects reassignment to another organization.

## Quarantine enforcement

- Inventory does not auto-assign or infer ownership.
- Proposal marks the project quarantined before approval.
- Approval clears quarantine only after the authoritative organization assignment succeeds.
- Unassigned and unresolved projects remain inaccessible to ordinary tenant users through the existing tenant-aware route filters.
- No first-user, username, email, project-name, or default-organization assignment is performed.
- Existing Level 3 compatibility boundaries remain intact.

## Security test results

Final focused command:

```text
mvn -q -Dtest=LegacyMigrationReviewServiceTest,TenantRouteIntegrationTest,TenantContextResolverTest,OrganizationStoreTest,AuthenticationFoundationTest,AuthVerificationManagerTest,TeamAuthorizationTest,TeamMemberManagerTest,LocalTwoAccountSecurityHarnessTest,ProjectOwnershipManagerTest,SharedPathConfigTest,LiveTwoAccountAuthorizationTest -Dqa.runner.liveBrowser=true test
```

Result: exit code `0`.

- Migration review tests: 5 passed.
- Tenant route tests: 2 passed.
- Tenant context tests: 5 passed.
- Organization persistence tests: 8 passed.
- Existing authentication/verification/RBAC/ownership/shared-path tests: 21 passed.
- Existing live browser authorization regression: 1 passed.
- Total selected tests: **42 passed, 0 failed, 0 errors, 0 skipped**.
- Maven compile: PASS.
- `git diff --check`: PASS.

The Selenium Chrome DevTools version warning remained non-fatal and did not fail the regression.

## Remaining risks

- The workflow is intentionally internal; no operator-facing migration API or UI exists yet.
- Full organization-aware migration of all resource families remains future work.
- Backup compensation is safe and hash-guarded, but it is not a distributed transaction across arbitrary external stores.
- Production readiness remains **NOT APPROVED** pending full organization APIs, frontend switching, complete resource integration, operational review controls, and production deployment validation.

Organization APIs, frontend tenant switching, and PostgreSQL migration have not started.
