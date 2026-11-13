# Level 4.1.8C-2A — H-005 First Remediation Batch

Status: **Batch C-2A PASS for the selected batch; H-005 OPEN overall**  
Production readiness: **NOT APPROVED**

## Scope and finding

The audit evidence identified H-005 as a shared execution/integration storage boundary problem. The first independently testable slice was selected as one closely related family:

- persisted `automationExecutions` result records;
- the H-004 background completion path that creates those records.

The root cause was that `AutomationExecutionManager.save` accepted arbitrary maps without authoritative organization/project ownership, while `RunnerServer.persistAutomationExecution` saved the result before assigning the ownership index. The GET route then authorized through a related test-case lookup rather than the persisted result owner. This created an inconsistent ownership boundary for malformed, legacy, or mismatched records.

The audit and selection evidence is in [level4.1.8c2a-h005-store-ownership-matrix.md](./level4.1.8c2a-h005-store-ownership-matrix.md) and [level4.1.8c2a-remediation-plan.md](./level4.1.8c2a-remediation-plan.md).

## Selected changes

Modified files for this batch:

- [AutomationExecutionManager.java](../src/main/java/com/example/unifiedqarunner/AutomationExecutionManager.java:40) now requires a valid UUID `organizationId` and nonblank `projectId` before writing a new execution record. Legacy records are not rewritten or silently assigned.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:567) now filters execution results through `automationExecutionVisible` before serialization. The helper validates stored organization/project metadata against `OrganizationStore` project ownership, then invokes the existing `TenantContextResolver` with `TEST_EXECUTION` read permission. Platform Admin behavior remains the existing explicit global-admin path.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2238) now writes immutable ownership metadata from `TenantJobContext`, validates the authoritative test-case/project relationship and organization assignment before saving, and assigns the ownership index only after those checks pass.
- [AutomationExecutionManagerTest.java](../src/test/java/com/example/unifiedqarunner/AutomationExecutionManagerTest.java:37) covers rejection of unowned records.
- [TenantRouteIntegrationTest.java](../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java:178) covers explicit ownership and pre-serialization tenant filtering.

## Before/after behavior

Before:

- A result map could be persisted without explicit tenant/project fields.
- The background path performed the save before ownership validation/index assignment.
- Result visibility depended on the related test-case ownership path, not the result record’s own authoritative owner.

After:

- New result records without valid ownership are rejected before the atomic store write.
- Background writes derive organization/project from immutable H-004 `TenantJobContext` and authoritative test-case/project and project/organization mappings.
- Missing, conflicting, or mismatched ownership is quarantined/denied and is not persisted as an accessible result.
- Reads validate the stored owner, authoritative project mapping, active session, active organization membership, project membership, and `TEST_EXECUTION` permission before serialization.
- Foreign records are excluded from lists; client-supplied organization parameters cannot select a different owner.
- Legacy records without trustworthy ownership remain inaccessible and are not auto-assigned.

## Security and regression validation

Focused selected-batch tests:

- `AutomationExecutionManagerTest`: **2/2 PASS**.
- `TenantRouteIntegrationTest`: **7/7 PASS**.
- Selected focused total: **9/9 PASS**.

Required bounded regression groups:

| Group | Result |
|---|---:|
| Maven compile (`-DskipTests compile`) | PASS |
| `TenantJobContextTest` | 4/4 PASS |
| `TenantContextResolverTest` | 6/6 PASS |
| `OrganizationApiIntegrationTest` | 3/3 PASS |
| `DatabaseConnectionManagerTest` (C-001) | 3/3 PASS |
| `TeamAuthorizationTest` | 6/6 PASS |
| `RunnerServerTest` | 3/3 PASS |
| `AuthenticationFoundationTest` | 4/4 PASS |
| `LocalTwoAccountSecurityHarnessTest` | 3/3 PASS |
| `ProjectOwnershipManagerTest` (H-001/H-002/H-003 ownership support) | 2/2 PASS |
| Combined targeted regression invocation | 34/34 PASS |
| `git diff --check` | PASS |

The selected route test provides direct cross-organization evidence: tenant B receives its `RUN-B` result and does not receive tenant A’s `RUN-A`; a tenant-B request narrowed to tenant A’s test case returns an empty list. The store test verifies records without ownership are rejected before writing. No real external provider or customer execution was used.

## H-004 and prior controls

H-004 `TenantJobContext` tests passed. The selected write path retains explicit immutable job ownership and validates it before result persistence. Existing C-001 database ownership, C-002 CI/CD ownership/redaction, H-001 legacy authorization, H-002 related-link IDOR controls, H-003 artifact deletion controls, authentication, CSRF, project RBAC, quarantine, last-owner protection, and platform Admin separation were regression-tested through the targeted groups available in this workspace. No changes were made to the C-002, H-002, or H-003 implementations.

## Deferred families and remaining H-005 exposure

The following remain outside this first batch and are not resolved:

- physical shared report, screenshot, video, log, attachment, and export paths;
- test-run history and broader execution stores;
- runner registration/configuration and distributed claims;
- CI/CD run/result caches and external provider state;
- database connection metadata beyond the existing C-001 controls;
- integration configuration and external connection stores;
- automation mappings and other legacy/unassigned resource families;
- PostgreSQL schema/storage migration;
- crash recovery and distributed worker recovery, which remain outside the in-memory H-004 architecture.

These boundaries require independent ownership designs and tests. H-005 is therefore **OPEN** and must not be marked fully resolved from this batch.

## Final disposition

- Batch C-2A: **PASS** for the selected automation execution/result ownership boundary.
- H-005 overall: **OPEN**.
- Remaining H-005 batches: deferred pending explicit approval.
- Production readiness: **NOT APPROVED**.

No PostgreSQL, cloud deployment, Docker, Windows packaging, port 8081 restart, or real external execution was performed.
