# Level 4.1.8C-1 — H-004 Final Report

Date: 2026-11-14  
Scope: durable-shaped tenant context and ownership revalidation for the existing in-memory run executor only.

## Result

**H-004: PASS for all identified in-scope run-queue boundaries.**  
**Level 4.1.8C-1: PASS within the current in-memory execution architecture.**  
**Production readiness: NOT APPROVED.**

H-005 was not implemented. The shared project-root stores and artifact directories remain outside this phase.

## Root cause

The audit found that `POST /api/runs` performed an initial request-time authorization check, then queued a `RunEntry` containing test and asset references but no immutable organization ID, project ID, or initiating user ID. Execution, cancellation, artifact attribution, and automation-result persistence therefore had no explicit async ownership envelope and did not revalidate membership at the sensitive boundary. The executor was also an unbounded single-thread executor.

The authoritative ownership source remains `OrganizationStore` project ownership, with project membership and permission decisions from `TenantContextResolver`/`TeamAuthorization`. Client organization parameters are not used to establish run ownership.

## Async entry points audited

| Entry point | Classification | Result |
|---|---|---|
| `RunnerServer.createRun` → `runExecutor` | Tenant-sensitive HTTP run queue | Fixed |
| `RunnerServer.executeRun` | Worker/plugin execution | Fixed with pre-execution revalidation |
| `RunnerServer.cancelRun` | Tenant-sensitive cancellation | Fixed with ownership revalidation |
| `RunnerServer.recordRunArtifacts` | Async artifact ownership attribution | Fixed with pre-write revalidation; physical storage remains H-005 |
| `RunnerServer.persistAutomationExecution` | Async result persistence | Fixed with pre-write revalidation |
| `ConnectionTestService` | Database connection test executor | Existing C-001 organization-owner gate; not part of H-004 run queue |
| `RunnerWindow` | Local desktop-only execution | No HTTP tenant boundary; unchanged |
| Newman/ExecutionService plugin HTTP executors | Plugin-local callback servers | No independent tenant job claim path found; shared integration storage remains H-005 |

No retry queue, durable worker claim protocol, scheduled job, webhook receiver, or distributed runner was found. No real test execution or external integration was started.

## Job context model

[`TenantJobContext`](../src/main/java/com/example/unifiedqarunner/TenantJobContext.java) is immutable and contains:

- immutable job ID;
- authoritative organization ID and project ID for tenant-owned runs;
- initiating user ID;
- job type and resource type/ID;
- creation timestamp;
- authorization policy version/name.

It contains no session ID, bearer token, password, OTP, raw credential, or live request object. Project jobs cannot be constructed without an organization. Platform-admin-only runs use an explicit separate `PLATFORM_ADMIN_RUN` policy and have no tenant project scope; they are revalidated as platform-admin jobs.

## Ownership and revalidation changes

- Run ownership is resolved before enqueue from the test-case ownership index and `OrganizationStore.findProjectOwnership`.
- The initial request must resolve through the existing tenant resolver with `TEST_EXECUTION` write permission for project-owned runs.
- The context is carried directly by `RunEntry`; no `ThreadLocal` or global current-organization state is used.
- The worker revalidates active user status, active organization status/membership, authoritative project mapping, explicit project membership, and current execution permission before plugin execution.
- Cancellation revalidates the same context before invoking the existing cancellation operation.
- Artifact attribution and automation-result persistence revalidate before writing ownership/result metadata.
- Missing, mismatched, deleted, suspended, revoked, quarantined, or ambiguous ownership fails closed.
- The executor is now a bounded single-worker `ThreadPoolExecutor` with a one-item queue and bounded shutdown through the existing server close path.
- No ownership is mutable through the job context, and no retry path can replace its tenant/project values.

## Files modified

- [`RunnerServer.java`](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) — bounded run executor; context creation; worker, cancellation, artifact and result revalidation; immutable context attached to `RunEntry`.
- [`TenantContextResolver.java`](../src/main/java/com/example/unifiedqarunner/TenantContextResolver.java) — added user-ID-based revalidation using the existing authorization rules, without reviving an HTTP session.
- [`TenantJobContext.java`](../src/main/java/com/example/unifiedqarunner/TenantJobContext.java) — immutable async ownership envelope.
- [`TenantJobContextTest.java`](../src/test/java/com/example/unifiedqarunner/TenantJobContextTest.java) — context invariants, tamper distinction, explicit concurrent context isolation.
- [`TenantContextResolverTest.java`](../src/test/java/com/example/unifiedqarunner/TenantContextResolverTest.java) — revoked-membership user-ID revalidation coverage.

## Persistence and restart behavior

The existing run queue and `RunEntry` map are in-memory. This phase does not claim crash recovery, restart recovery, retry durability, or cross-process worker claims. On process restart, in-memory runs are lost as before. The ownership context is durable-shaped and immutable within the current job lifetime; a future persistent queue must serialize and validate the same fields rather than infer ownership from a run ID or filename.

Shared report, screenshot, video, and integration storage was not migrated. Unauthorized retrieval remains blocked by the existing ownership index, while physical tenant namespaces remain H-005 scope.

## Test results

All commands were sequential, bounded, and used disposable fixtures. No PostgreSQL, browser, cloud, port 8081 restart, or real external execution was used.

| Validation | Result |
|---|---:|
| Maven compile (`mvn -q -DskipTests compile`) | PASS |
| H-004 context tests | 4/4 PASS |
| Tenant context and revocation tests | 6/6 PASS |
| Tenant route tests | 6/6 PASS |
| Organization API tests | 3/3 PASS |
| Database ownership regression | 3/3 PASS |
| Team authorization regression | 6/6 PASS |
| RunnerServer tests | 3/3 PASS |
| Authentication regression | 4/4 PASS |
| Two-account isolation regression | 3/3 PASS |
| Project ownership regression | 2/2 PASS |
| Automation execution persistence regression | 1/1 PASS |
| Combined requested tenant/security regression batch | 28/28 PASS |
| `git diff --check` | PASS; only normal Git LF/CRLF warnings |

The focused H-004 evidence covers immutable context invariants, project/org requirement, explicit concurrent context separation, tamper distinction, and revocation-at-revalidation. Actual browser execution was intentionally not run because it would invoke a real plugin/test path; no real external test suite was needed to verify this source-level boundary.

## Prior security controls

C-001 database connection ownership, C-002 CI/CD ownership/redaction, H-001 legacy route authorization, H-002 related-link IDOR protection, H-003 artifact deletion ownership, authentication/session/CSRF, project membership, last-owner protection, quarantine behavior, and platform-admin separation remained green in the targeted regression batch. No H-005 store migration or authorization bypass was introduced.

## Remaining high findings and gaps

- **H-005 remains unresolved/deferred:** shared process/project-root stores, integrations, and physical artifact namespaces still require a separate tenant-scoped storage architecture.
- Crash/restart recovery and durable persisted job records are not implemented because the current queue is in-memory.
- Distributed runner registration, worker claim authorization, and retry persistence are not implemented.
- Full browser E2E and external-provider verification remain out of scope for this phase.

## Final status

H-004 is **PASS** for every currently identified in-scope asynchronous run boundary, with restart limitations documented rather than overstated.  
Level 4.1.8C-1 is **PASS**.  
Level 4.1.8C-2/H-005, Level 4.1.8D, Level 4.1.8E, PostgreSQL, cloud deployment, and production approval were not started.

**Production readiness: NOT APPROVED.**
