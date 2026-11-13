# Level 4.1.8B-2 Batch B Final Report

Date: 2026-11-14  
Project: Unified QA Runner

## Status

Batch B is **PASS for the selected findings**. Overall Level 4.1.8B-2 remains **IN PROGRESS** because three High findings are intentionally deferred. Production readiness remains **NOT APPROVED**.

No browser, PostgreSQL, cloud deployment, external provider, port 8081, or Windows packaging activity was used.

## Findings selected

### H-002 — Related-link mutation lacked object ownership enforcement

Before the fix, `PUT` and `DELETE /api/user-stories/related-links/{id}` validated same-origin/CSRF but trusted the link ID through to mutation. The route did not resolve the link to its story and authoritative project.

After the fix, [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:959) resolves the link, derives the story project server-side, requires a resolved project-to-organization assignment, and calls `TenantContextResolver.resolveProject` with `USER_STORIES` write permission. Missing links return 404; inactive membership, wrong organization, wrong project, or insufficient permission return 403. Payloads cannot change ownership.

### H-003 — Bulk screenshot/video deletion lacked ownership filtering

Before the fix, [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3186) and [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:3224) deleted every regular file in the shared directory after only the route-level authentication/CSRF gate.

After the fix, each candidate file is checked through the existing artifact ownership index and tenant resolver before deletion. Resolved authorized project artifacts may be deleted; artifacts owned by another organization/project and unresolved legacy artifacts are preserved. Existing platform-admin behavior remains separate through the existing platform-admin shortcut.

## Exact modified files

- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) — related-link ownership enforcement; tenant-aware artifact visibility and bulk deletion filtering.
- [TenantRouteIntegrationTest.java](../../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java) — disposable-storage tests for H-002/H-003 and HTTP authorization contracts.
- [level4.1.8b2-high-findings-review.md](./level4.1.8b2-high-findings-review.md) — Batch A evidence review and remediation plan.

No other application resource family was modified.

## Authorization evidence

H-002 focused test evidence:

- Authorized project member in the owning organization: update **200**, delete **204**.
- Member from another organization attempting the same link: **403**.
- Invalid link ID: **404**.
- Missing CSRF: **403**.
- Unauthenticated mutation: **401**.
- Story/project ownership is resolved server-side; no client ownership field is accepted.

H-003 focused test evidence:

- Authorized project member list contains only the artifact from that member’s project: **200**.
- Artifact from another organization and unassigned legacy artifact are absent from the list.
- Missing CSRF on bulk delete: **403**.
- Authorized bulk delete removes only the authorized tenant’s artifact: **200**.
- Other-tenant and unassigned artifacts remain on disk.
- Unauthenticated bulk delete: **401**.

The existing `ProjectOwnershipManager`, `TenantContextResolver`, `TeamAuthorization`, CSRF, same-origin, session and platform-admin boundaries were retained. No ownership reassignment or automatic legacy assignment was introduced.

## Validation results

- New focused Batch B tests: **5/5 passed**, 0 failures, 0 errors.
- Final bounded tenant/organization/Level 3 regression set: **39/39 passed**, 0 failures, 0 errors.

Final regression classes:

- `TenantRouteIntegrationTest`
- `TenantContextResolverTest`
- `OrganizationApiIntegrationTest`
- `OrganizationStoreTest`
- `ProjectOwnershipManagerTest`
- `TeamAuthorizationTest`
- `RunnerServerTest`
- `AuthenticationFoundationTest`
- `LocalTwoAccountSecurityHarnessTest`

- Maven compile: **PASS** (`mvn -q -DskipTests compile`).
- `git diff --check`: **PASS**; no whitespace errors reported.
- One harmless existing HTTP-server warning was emitted for a 204 response; it did not produce a test failure or error.

## Deferred findings

- **H-001:** Legacy project authorization remains split from tenant resolution. Deferred because it requires broad route consolidation and compatibility review.
- **H-004:** Durable tenant context for asynchronous runs remains unresolved. Deferred because it requires execution model and retry/revocation architecture changes.
- **H-005:** Shared process/project-root stores and integrations remain insufficiently tenant-scoped. Deferred because it depends on broader resource ownership and storage design.

Remaining High findings: **3**. Remaining Critical findings from 4.1.8A: **0**.

## Regression failures and gaps

No failures remain in the targeted validation set. Full browser E2E, execution-worker isolation, CI/CD runner isolation, reports/exports, external integrations, PostgreSQL and production deployment were not started and are outside Batch B.

## Gate decision

**Batch B: PASS for H-002 and H-003.**

**Level 4.1.8B-2 overall: IN PROGRESS.** Explicit approval is required before Batch C, Level 4.1.8B-3, Level 4.1.8C, Level 4.1.8D, Level 4.1.8E, or PostgreSQL migration.

Production readiness: **NOT APPROVED**.
