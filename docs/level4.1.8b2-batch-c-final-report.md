# Level 4.1.8B-2 Batch C Final Report — H-001

Date: 2026-11-14  
Project: Unified QA Runner

## Status

Batch C is **PASS for H-001 within the affected and verified legacy project route families**. Level 4.1.8B-2 overall remains **IN PROGRESS** because H-004 and H-005 are intentionally deferred. Production readiness remains **NOT APPROVED**.

No browser, PostgreSQL, cloud deployment, external provider, port 8081, or Windows packaging activity was used.

## H-001 root cause

The legacy `requireProjectAccess` and `projectAccessForDomain` helpers authorized directly through `TeamAuthorization` and the global team-member store. Resolver-backed routes separately validated active organization membership, authoritative project-to-organization ownership and explicit project membership. This created two authorization contracts: a project ID could be accepted by a legacy route without the resolver proving its organization owner and active tenant membership.

Evidence was confirmed in the current source at [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2873), [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2877), [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2886), and [TenantContextResolver.java](../../src/main/java/com/example/unifiedqarunner/TenantContextResolver.java:79).

## Affected routes

The complete route matrix is in [level4.1.8b2-batch-c-legacy-route-matrix.md](./level4.1.8b2-batch-c-legacy-route-matrix.md). The consolidated families include:

- Wiki-space creation.
- Defect list, create, update and nested link operations.
- User-story, epic and sprint project reads/creates.
- Story nested activity, comments, attachments, related links, test-case links, archive and restore operations.
- Sprint board and sprint operations, including parent checks for sprint/story assignment.
- Shared-path creation and GitHub/ZIP import authorization.
- Test-case creation paths that derive project ownership from the request or story relationship.

Platform-admin ownership migration and explicitly unassigned/admin-only compatibility routes were preserved as separate classes. Legacy compatibility routes without authoritative ownership remain unverified and were not silently converted into tenant resources.

## Files modified

- [RunnerServer.java](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java) — centralized legacy project authorization on `TenantContextResolver`; added authoritative parent checks for epic and sprint mutations; preserved admin/quarantine paths.
- [TenantRouteIntegrationTest.java](../../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java) — H-001 route authorization tests and prior C/H regression coverage.
- [level4.1.8b2-batch-c-legacy-route-matrix.md](./level4.1.8b2-batch-c-legacy-route-matrix.md) — legacy route matrix.
- [level4.1.8b2-batch-c-final-report.md](./level4.1.8b2-batch-c-final-report.md) — this report.

No H-004 or H-005 implementation was made.

## Authorization consolidation

The consolidated helper now:

1. Requires a nonblank project ID.
2. Resolves the project’s authoritative organization through `OrganizationStore`.
3. Rejects missing or quarantined ownership.
4. Resolves the authenticated session server-side.
5. Requires active organization membership.
6. Requires explicit project membership.
7. Enforces the requested domain read/write permission through `TenantContextResolver`.
8. Validates an optional client organization ID against the server-derived assignment.
9. Does not use a direct `TeamAuthorization` fallback for legacy project routes.

Epic and sprint object mutations now resolve their parent project before mutation. Sprint/story assignment validates both parent resources. No client payload can reassign ownership through these checks.

Authentication, CSRF, same-origin, session, last-owner protection, platform-admin separation, H-002 related-link authorization and H-003 artifact filtering were retained.

## Before/after behavior

| Scenario | Before | After |
|---|---|---|
| Authorized member accesses assigned project route | Direct team authorization | Resolver-backed organization + project authorization; legitimate access preserved |
| User from another organization supplies project ID | Legacy helper could rely on project/team state without tenant proof | 403 after authoritative organization membership check |
| User supplies another organization ID with an assigned project | Client value was not consistently bound to route authorization | 403 when supplied organization conflicts with server-derived ownership |
| User lacks explicit project membership within an active organization | Direct helper could use broader team role behavior | 403; project membership remains required |
| Missing project ID | Route-specific legacy behavior | 403 fail-closed |
| Unassigned/quarantined project ownership | Legacy fallback could reach direct helper | 403; no ownership inference or automatic assignment |
| Epic/sprint object mutation by another project | Some branches lacked parent authorization | 403/404 before mutation |

## Test results

H-001 focused tests:

- `TenantRouteIntegrationTest`: **6/6 passed**.
- Coverage included authorized legacy project access, wrong organization, cross-organization access, cross-project access, missing project scope, unauthenticated access, CSRF protection, authorized creation, and denial response behavior.

Prior security regression groups:

- C-001/C-002: `TenantRouteIntegrationTest`, `DatabaseConnectionManagerTest`, `TenantContextResolverTest`, `ProjectOwnershipManagerTest` — **16/16 passed**.
- H-002/H-003: `TenantRouteIntegrationTest` — **6/6 passed**.
- Organization authorization: `OrganizationApiIntegrationTest`, `OrganizationStoreTest` — **11/11 passed**.
- Level 3 authentication/RBAC: `AuthenticationFoundationTest`, `TeamAuthorizationTest`, `RunnerServerTest`, `LocalTwoAccountSecurityHarnessTest` — **16/16 passed**.

Combined final targeted validation: **43/43 tests passed; 0 failures; 0 errors**.

Additional validation:

- Maven compile: **PASS** (`mvn -q -DskipTests compile`).
- `git diff --check`: **PASS**; no whitespace errors reported.
- Disposable temporary storage and ephemeral server ports were used by integration tests.
- One existing HTTP-server warning for a 204 response was emitted; it did not cause a failure or error.

## Remaining findings and boundaries

- H-001: **resolved within the affected, resolver-consolidated legacy route families tested in this batch**.
- H-004: **deferred and unresolved** — durable organization/project/requester context for asynchronous runs, retries, cancellation and worker boundaries.
- H-005: **deferred and unresolved** — process/project-root-scoped stores, integrations, artifacts and broader resource ownership schema.
- Critical findings: **0 remaining from the verified 4.1.8A set**.

Remaining unverified routes include compatibility `/api/stories*` and `/api/suites*` paths without authoritative project ownership, process-level maintenance operations, sprint branches not backed by a durable ownership field, and future runner/webhook boundaries. These were documented rather than granted permissive fallback access.

## Gate decision

**Batch C: PASS for H-001.**

**Level 4.1.8B-2 overall: IN PROGRESS.** H-004 and H-005 remain deferred. Do not begin Level 4.1.8B-3, Level 4.1.8C, Level 4.1.8D, Level 4.1.8E, PostgreSQL migration, cloud deployment or production approval without explicit authorization.

Production readiness: **NOT APPROVED**.
