# P1-F12I RBAC, Organization Switching, and Security Finding Audit

Date: 2026-11-15
Branch: `master`
HEAD at preflight: `4c273b99b237546bcec0b98506e47b39dd3400ec`

## Safety and provenance

The worktree was already dirty with prior authentication, frontend, test, and report changes. Those changes were preserved and are not attributed to this phase. This phase added only [P1F12IRbacOrganizationBrowserE2ETest.java](./src/test/java/com/example/unifiedqarunner/P1F12IRbacOrganizationBrowserE2ETest.java) and this audit/report updates.

All execution used disposable storage, synthetic verified users, one in-process `RunnerServer(root, 0, ...)`, one headless Chrome worker, and a dynamically assigned loopback port. No live data, SMTP, external integrations, PID 23304, or port 8081 were used by the isolated tests.

## RBAC policy and results

Policy was derived from [TeamAuthorization.java](./src/main/java/com/example/unifiedqarunner/TeamAuthorization.java):

| Role | User Stories | Sprint Board | Test Cases | Defects |
|---|---|---|---|---|
| Admin | read/write | read/write | read/write | read/write |
| Project Manager | read/write | read/write | read/write | read/write |
| Product Owner | read/write | read/write | read-only | read-only |
| Viewer | read-only | read-only | read-only | read-only |

The isolated browser test verified each role’s `/api/team-permissions` snapshot against this policy. Viewer status mutation returned HTTP 403. Result: **PASS**.

## Organization switching and stale context

The synthetic multi-tenant user had active membership in Organizations A and B and explicit project membership in both. Browser verification showed:

- Organization A listing contained only Project A.
- Switching to Organization B selected only authorized Project B.
- The prior Project A identifier was not retained in browser storage.
- The prior Organization A sprint identifier was not sent in subsequent board requests.
- Organization B listing contained Project B and did not contain Project A.

The source implementation in `index.html` aborts the previous tenant controller, increments a generation guard, clears `tenantState.projectId`, resets module collections, and reloads the new tenant. Result: **PASS** for the tested switching path.

## Project restore UX

The backend supports `POST /api/user-stories/projects/{id}/restore` and requires an authenticated `Admin` through [RunnerServer.java](./src/main/java/com/example/unifiedqarunner/RunnerServer.java). The frontend currently exposes an Archive action in `archiveUserStoryProject`, but no corresponding project Restore button or archived-project discovery control was found in [index.html](./src/main/resources/static/index.html). Therefore:

- API-level Admin restore: previously verified in P1-F12H, **PASS**.
- Dedicated visual project restore flow: **NOT VERIFIED / CAPABILITY GAP**.
- Viewer restore denial: covered by the server-side Admin gate; no UI bypass was introduced.

No production UI change was applied because adding a restore workflow requires a separate product/UX approval and archived-project discovery policy.

## Security finding audit

### D1-F-001 — OPEN

D1-F-001 is the High ZIP-import control gap documented in [level4.1.8d-d1-f-001-final-report.md](./docs/level4.1.8d-d1-f-001-final-report.md): the user ZIP import path has request/input limits and ZIP-Slip/path controls, but exact decompressed-byte, entry-count, nesting, and compression-ratio evidence remains unresolved. The existing decision records that parser/API semantics were not sufficiently proven and that a maintained parser/dependency evaluation or explicit risk acceptance is required. This phase did not inspect or modify ZIP processing.

Status remains **OPEN**.

### D1-F-002 — OPEN

D1-F-002 concerns whole-file screenshot/video buffering in the original audit. The approved remediation reports identify fixed 16 KiB incremental streaming in both media handlers with authorization and containment checks before delivery. The formal closure decision remains **NOT ELIGIBLE — VERIFICATION GAPS**, including disconnect/error-path evidence and real-path/symlink limitations; the gap-completion reports document the later focused cases. This phase did not modify media streaming or path security.

Status remains **OPEN**.

## Validation

| Command | Result |
|---|---|
| `mvn -q -Dtest=P1F12IRbacOrganizationBrowserE2ETest test` | 2/2 PASS, exit 0 |
| `mvn -q -Dtest=UserStoryHttpSecurityTest test` | 5/5 PASS, exit 0 |
| `mvn -q -Dtest=P1F12EBrowserE2ETest test` | 6/6 PASS, exit 0 |
| `mvn -q -DskipTests compile` | PASS, exit 0 |
| `git diff --check` | PASS; existing line-ending warnings only |

Selenium emitted the known Chrome 157 / Selenium 4.27 CDP compatibility warning; it did not fail any test.

## Runtime and cleanup

After testing, PID 23304 remained alive as the expected Java process, `127.0.0.1:8081` remained owned by PID 23304, and HTTP health remained 200. Test-owned browser/server resources were closed and no `qa-p1-f12i-browser-*` temporary roots remained.

## Final classification

- RBAC role snapshots: **PASS**.
- Viewer unauthorized mutation: **PASS**.
- Organization switching and stale project/sprint context: **PASS** for the tested two-organization path.
- Project restore API: **PASS** for authorized Admin behavior.
- Project restore UI: **NOT VERIFIED / CAPABILITY GAP**.
- Full Product Owner/Project Manager browser CRUD matrix: **PARTIALLY VERIFIED**; policy snapshots passed, complete module mutation coverage remains untested.
- D1-F-001: **OPEN**, unchanged.
- D1-F-002: **OPEN**, unchanged.
- H-005: **CLOSED**, unchanged.
- Production readiness: **NOT APPROVED**.
