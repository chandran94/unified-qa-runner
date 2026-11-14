# Level 4.1.8C-2D — H-005 Runner Security and Tenant Isolation

Batch C-2D: **PASS — audit complete; no existing runner-specific remediation target**  
H-005 overall: **OPEN**  
Production readiness: **NOT APPROVED**

## Existing runner capabilities discovered

The source contains a local in-process runner only:

- `GET /api/status` reports the local `runnerEnabled` state.
- `POST /api/enable` changes the local enabled flag under the existing browser mutation protections.
- `POST /api/runs` creates a local run in the bounded in-memory executor.
- `GET /api/runs`, `GET /api/runs/{id}`, `DELETE /api/runs/{id}`, and `GET /api/runs/{id}/report` are human-session routes with existing tenant/project ownership gates.
- `RunnerServer.executeRun`, `cancelRun`, `recordRunArtifacts`, and `persistAutomationExecution` are local/background paths covered by H-004, C-2A, and C-2C.

No runner registration endpoint, remote runner identity, runner token, heartbeat handler, job-claim/polling route, runner assignment record, remote result callback, or runner configuration store was found. The complete capability matrix is in [level4.1.8c2d-runner-ownership-matrix.md](./level4.1.8c2d-runner-ownership-matrix.md).

## Risk selection

There is no existing runner-specific resource family to rank or remediate. The current queue is explicitly in-memory and single-process. Creating a distributed runner platform, token lifecycle, durable claims, or remote callbacks would be new architecture, not remediation of an existing H-005 boundary, and was not performed.

## Source verification

- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:605) contains local run listing and tenant filtering.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:712) contains local run creation.
- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java:121) creates the bounded local executor.
- [TenantJobContext.java](../src/main/java/com/example/unifiedqarunner/TenantJobContext.java:1) carries current background ownership context.
- [ExecutionService.java](../src/main/java/com/example/unifiedqarunner/ExecutionService.java:46) returns local run results; it has no remote runner identity or claim API.

## Changes and validation

No application source, tests, or security behavior were modified in C-2D because the requested runner-specific resource family does not exist. The prior C-2B/C-2C working-tree changes were preserved.

No new C-2D compile or test execution was required after the no-change audit. Relevant prior evidence remains recorded in [C-2C final report](./level4.1.8c2c-final-report.md), including 48/48 targeted tests, Maven compile, and `git diff --check` for the preceding artifact batch. This report does not claim those prior commands as new C-2D executions.

## Deferred boundaries

- Remote runner registration and service identity.
- Runner token issuance, verifier storage, rotation, revocation, and expiry.
- Heartbeat/status records.
- Remote job claiming, assignment, double-claim prevention, and distributed coordination.
- Remote result submission and callback authenticity/replay protection.
- Durable queue recovery and multi-node worker coordination.
- Existing non-runner H-005 artifact, integration, and shared-store boundaries.

Unassigned future runner records must fail closed and must not be assigned from filenames, display names, client IDs, or inferred tenant context.

## Final status

- Batch C-2D: **PASS as an audit-only gate; remediation not applicable**.
- New runner-family vulnerabilities: **none identified because the runner resource families are not implemented**.
- H-005 overall: **OPEN**.
- C-2E, C-3, Level 4.1.8D, Level 4.1.8E, distributed runner architecture, durable queue work, PostgreSQL, and cloud deployment: not started.
- Production readiness: **NOT APPROVED**.
