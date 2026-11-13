# Level 4.1.8A Execution, Background and Integration Security Audit

## Lifecycle trace

```text
HTTP session
  -> RunnerServer route gate / CSRF
  -> legacy project check or TenantContextResolver
  -> in-memory single-run executor
  -> plugin / ProcessBuilder execution
  -> shared reports, screenshots, videos and logs
  -> run/automation persistence
  -> report/status/artifact retrieval
```

### Request to queue

`POST /api/runs` reaches [`RunnerServer.java:1786`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1786). A run with a test case requires owned project access at [`RunnerServer.java:1822`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1822)–[`RunnerServer.java:1825`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1825). A run without a test case is reserved for the global admin. Asset discovery filters shared paths/imports through legacy ownership at [`RunnerServer.java:1849`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1849)–[`RunnerServer.java:1861`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1861).

**Assessment:** the entry gate is fail-closed for ordinary unowned execution, but the queued object does not carry an explicit immutable organization ID, project ID, requester ID, or authorization snapshot. Project ownership is inferred later from the test case at [`RunnerServer.java:1954`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1954)–[`RunnerServer.java:1956`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1956). Revocation between queue and execution is not revalidated at the sensitive boundary.

### Worker and plugin execution

The single executor is created at [`RunnerServer.java:116`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:116)–[`RunnerServer.java:119`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:119). Plugins use local files and `ProcessBuilder`; no runner registration, worker claim token, tenant-bound worker credential, or distributed queue was found. The current implementation is single-process, so “another tenant’s runner claims this job” is not implemented, but the future boundary is unverified.

### Results, artifacts and reports

`executeRun` records artifacts and persists automation results in the async completion path at [`RunnerServer.java:1963`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1963)–[`RunnerServer.java:1997`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1997). Individual artifact access resolves a project through `ProjectOwnershipManager`; report access checks `TEST_RUN` ownership before serving the report. The shared directories are still not physically tenant-separated, and collection deletion bypasses ownership (H-003).

### Cancellation and retries

Cancellation uses the in-memory `activeRunId` and a global `ExecutionService.cancel()` at [`RunnerServer.java:2001`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2011). There is no durable tenant context in the cancellation record and no retry queue. Test coverage for cancellation IDOR, post-revocation cancellation, restart behavior and retry context is **NOT VERIFIED**.

## Files and path handling

- Screenshot/video path traversal checks reject `..`, separators and non-regular files; individual reads then enforce artifact ownership.
- Attachment upload limits size, normalizes the generated storage filename and checks the target parent at [`RunnerServer.java:4740`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:4784).
- Attachment download uses the stored generated filename and checks the normalized parent at [`RunnerServer.java:4790`](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:4798).
- Symlink-specific behavior, backup/restore tenant isolation, retention, and secure deletion are not tested. `Files.isRegularFile` is used, but a complete symlink policy is not documented.

## CI/CD and external providers

GitHub/Jenkins calls are outbound and timeout-bounded in the inspected code. The key risks are ownership and secret boundaries, not transport availability: one shared config/token slot, source-embedded encryption key, and token-prefix logging. No webhook receiver or signature validator was found. Pipeline status and sync are therefore pull-based and rely on the singleton configuration.

## AI assistant

`POST /api/ai/ask` is authenticated and CSRF-protected, but the response is generated from the submitted query and is not stored. No tenant data retrieval, conversation ID, provider call, or result ownership exists in the inspected code. This is **not a demonstrated cross-tenant data leak**, but it is not a tenant-aware assistant boundary.

## Verification classification

| Boundary | Classification | Evidence |
|---|---|---|
| Auth/session/CSRF | PARTIALLY VERIFIED | Authentication foundation and existing focused tests |
| Organization membership and revoke | PARTIALLY VERIFIED | Organization API/browser tests; broader session race NV |
| Project ownership resolver | PARTIALLY VERIFIED | Resolver and tenant-route tests |
| Run creation ownership | PARTIALLY VERIFIED | Source gate; no complete async tenant suite |
| Worker claim/runner registration | NOT IMPLEMENTED | No model/endpoint found |
| Async tenant propagation/revocation | NOT VERIFIED | No durable context or focused tests |
| Run/report retrieval | PARTIALLY VERIFIED | Ownership checks in route; restart/IDOR matrix NV |
| Screenshot/video individual access | PARTIALLY VERIFIED | Ownership and path code; no full browser matrix |
| Bulk artifact delete | NOT VERIFIED / GAP | Route deletes shared directory without owner filter |
| Attachments | PARTIALLY VERIFIED | Relationship ownership and path checks; symlink/retention NV |
| CI/CD | NOT VERIFIED / GAP | Shared singleton and secret model |
| Database connections | NOT VERIFIED / GAP | Shared store and membership-only authorization |
| Webhooks/SSE/WebSockets | NOT IMPLEMENTED | No matching endpoints found |
| Backup/restore | NOT VERIFIED | Organization backup exists; resource-wide tenant restore not established |

