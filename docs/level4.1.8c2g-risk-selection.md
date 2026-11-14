# Level 4.1.8C-2G — Residual Risk Selection

## Selected family

**Project-scoped CI/CD pipeline status and auto-sync cache.**

Affected routes:

- `GET /api/cicd/status`
- `POST /api/testcases/sync` when the request uses the GitHub source or auto-sync branch

Affected classes:

- `RunnerServer.getCiCdStatus(String projectId)`
- `RunnerServer.syncTestCases(HttpExchange, String projectId)`
- `PipelineStatusService.getRuns(CiCdSettings)`
- `PipelineStatusService.getLatestStatus(CiCdSettings)`

## Evidence and root cause

The routes already resolve and authorize a project before loading its CI/CD settings. However, the status service cached runs with a key composed only of platform and repository/Jenkins URL. Two authorized projects using the same external repository or Jenkins URL could therefore receive the same cached run list. This is a confirmed shared execution-history boundary, not a speculative provider vulnerability. The original cache also affected the auto-sync branch because it called the unscoped overload.

## Ownership contract

The route-resolved `projectId` is the authoritative server-side scope. Existing project-to-organization membership and CI/CD permission checks remain the authorization boundary. The cache must include that project scope; unscoped direct service calls must not read or populate a shared cache. No client organization ID is used as authorization proof.

## Planned minimal change

Add project-scoped overloads to the existing status service, include the project ID in the cache key, disable cache use for unscoped compatibility calls, and pass the already-authorized project ID from both affected server paths. No provider calls, credentials, storage migrations, RBAC rules, or prior security fixes are changed.

## Expected behavior

- Authorized users continue to read status for their project.
- Status for Project A cannot satisfy Project B's cache lookup, even when provider/repository settings match.
- Auto-sync uses the same project-scoped cache boundary.
- Unscoped service calls are fail-safe with respect to caching.
- Existing authentication, project RBAC, CSRF and secret redaction remain unchanged.

## Required focused verification

1. Assert cache keys differ for two project IDs with identical provider settings.
2. Compile the application.
3. Run the focused test and the existing tenant/security regression group.
4. Run `git diff --check`.

## Deferred families

Artifact/report families already covered by C-2C, remote imports covered by C-2E, shared catalog paths covered by C-2F, and all remaining partially remediated/unverified JSON-backed CRUD, logs, wiki, attachment and durable-result families are deferred. No second family is included in this batch.
