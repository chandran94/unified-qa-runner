# Level 4.1.8B-2 Batch C — H-001 Legacy Route Authorization Matrix

The rows below group endpoints only when they share the same authorization helper and ownership contract. Source references are current after the H-001 change.

## Consolidated class A routes — authenticated tenant/project resources

| Method and route | Auth / CSRF | Organization and project contract | Ownership source | Before / after | Expected denial |
|---|---|---|---|---|---|
| POST `/api/wiki/spaces` ([RunnerServer.java:350](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:350)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `WIKI` write | Body project ID → `OrganizationStore` project assignment | Direct team authorization → resolver-backed project authorization | 401 unauthenticated; 403 missing membership/scope |
| GET `/api/defects?projectId=...` ([RunnerServer.java:897](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:897)) | Session | Active organization membership; explicit project membership; `DEFECTS` read | Query project is validated against server assignment | Direct team authorization → resolver-backed; list filters through the same helper | 401/403 |
| POST `/api/defects` ([RunnerServer.java:906](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:906)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `DEFECTS` write | Body project ID | Direct team authorization → resolver-backed | 401/403 |
| PUT `/api/defects/{id}` ([RunnerServer.java:940](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:940)) | Session; same-origin and CSRF | Parent defect project plus any supplied project ID must authorize | Defect `projectId`; body project cannot reassign ownership | Parent and optional project checks now share resolver-backed helper | 401/403/404 |
| POST `/api/defects/{id}/links` and DELETE `/api/defects/{id}/links/{target}` ([RunnerServer.java:913](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:913)) | Session; same-origin and CSRF | Parent defect project; `DEFECTS` write | Defect object project | Nested parent check delegates to consolidated helper | 401/403/404 |
| GET `/api/defects/{id}` ([RunnerServer.java:921](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:921)) | Session | Parent defect project; `DEFECTS` read | Defect object project | Nested parent check delegates to consolidated helper | 401/403/404 |
| GET `/api/user-stories/epics?projectId=...` ([RunnerServer.java:1015](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1015)) | Session | Active organization membership; explicit project membership; `USER_STORIES` read | Query project validated against `OrganizationStore` | Direct team authorization → resolver-backed | 401/403 |
| POST `/api/user-stories/epics` ([RunnerServer.java:1020](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1020)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `USER_STORIES` write | Body project ID | Direct helper now resolver-backed | 401/403 |
| PUT/POST archive/restore `/api/user-stories/epics/{id}` ([RunnerServer.java:1025](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1025)) | Session; same-origin and CSRF | Parent epic project; `USER_STORIES` write | Epic `projectId` resolved server-side | Added parent-project guard; no client ownership reassignment | 401/403/404 |
| GET `/api/user-stories/stories?projectId=...` ([RunnerServer.java:1042](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1042)) | Session | Active organization membership; explicit project membership; `USER_STORIES` read | Query project validated server-side | Direct helper now resolver-backed; list is serialized only after authorization | 401/403 |
| POST `/api/user-stories/stories` ([RunnerServer.java:1078](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1078)) | Session; same-origin and CSRF | Body project must resolve to active tenant/project permission | Body project ID validated against assignment | Direct helper now resolver-backed | 401/403 |
| GET `/api/user-stories/work-items?projectId=...` ([RunnerServer.java:1072](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1072)) | Session | Active organization membership; explicit project membership; `USER_STORIES` read | Query project | Direct helper now resolver-backed; list is filtered by authorized project | 401/403 |
| PUT/POST status/activity/comments/attachments/related-links/test-cases/archive/restore under `/api/user-stories/stories/{id}` ([RunnerServer.java:1083](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1083)) | Session; mutations same-origin and CSRF | Parent story project; read/write `USER_STORIES` permission | Story `projectId` resolved server-side | Existing nested helper now reaches consolidated resolver-backed helper | 401/403/404 |
| PUT/DELETE `/api/user-stories/related-links/{id}` ([RunnerServer.java:962](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:962)) | Session; same-origin and CSRF | Parent story project; `USER_STORIES` write | Link → story → project | H-002 protection retained; resolver-backed parent authorization | 401/403/404 |
| GET `/api/user-stories/sprints?projectId=...` and GET `/api/user-stories/board?projectId=...` ([RunnerServer.java:1207](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1207), [RunnerServer.java:1248](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1248)) | Session | Active organization membership; explicit project membership; `SPRINT_BOARD` read | Query project | Direct helper now resolver-backed | 401/403 |
| POST `/api/user-stories/sprints` ([RunnerServer.java:1212](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1212)) | Session; same-origin and CSRF | Body project; `SPRINT_BOARD` write | Body project ID | Direct helper now resolver-backed | 401/403 |
| PUT/POST start/complete `/api/user-stories/sprints/{id}` ([RunnerServer.java:1217](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1217)) | Session; same-origin and CSRF | Parent sprint project; `SPRINT_BOARD` write | Sprint `projectId` resolved server-side | Added parent-project guard | 401/403/404 |
| POST/DELETE `/api/user-stories/sprints/{id}/stories` ([RunnerServer.java:1234](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:1234)) | Session; same-origin and CSRF | Sprint and story must each authorize; store enforces project relationship | Sprint and story project IDs | Added both parent checks; no cross-project assignment | 401/403/404 |
| POST `/api/shared-paths` ([RunnerServer.java:614](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:614)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `TEST_EXECUTION` write | Body project ID | Direct helper now resolver-backed | 401/403 |
| POST `/api/imports/github` ([RunnerServer.java:647](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:647)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `TEST_EXECUTION` write | Body project ID | Direct helper now resolver-backed before outbound work | 401/403 |
| POST `/api/imports/zip?projectId=...` ([RunnerServer.java:652](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:652)) | Session; same-origin and CSRF | Active organization membership; explicit project membership; `TEST_EXECUTION` write | Query project ID validated against server assignment | Direct helper now resolver-backed | 401/403 |
| POST test-case creation path ([RunnerServer.java:795](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:795), [RunnerServer.java:4451](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:4451)) | Session; same-origin and CSRF | Requested/story-derived project; `TEST_CASES` write | Project ID or story relationship | Direct helper now resolver-backed | 401/403 |
| POST `/api/user-stories/epics`, `/api/user-stories/stories`, `/api/user-stories/sprints` creation helpers ([RunnerServer.java:5007](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:5007)) | Session; same-origin and CSRF | Body project ownership resolved server-side | Body project ID | Central helper consolidation | 401/403 |

## Class B routes — platform-admin-only or intentionally unassigned

| Route family | Current contract | H-001 action |
|---|---|---|
| `/api/admin/project-ownership/*` ([RunnerServer.java:322](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:322)) | Authenticated platform Admin plus CSRF for mutation; explicit admin migration/quarantine operation | Unchanged; remains the only ordinary ownership assignment path |
| `/api/user-stories/projects` create/update/archive/restore ([RunnerServer.java:989](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:989)) | Authenticated Admin and CSRF; project lifecycle is administrative | Unchanged; organization project APIs remain the tenant-owned lifecycle |
| `/api/database/overview`, `/api/testcases/stats`, `/api/testcases/sync`, `/api/testcases/bulk-delete`, `/api/automation-assets/scenarios` | Existing `requireAdminForUnownedResource` quarantine boundary | Unchanged; unresolved data is not granted to ordinary tenant users |

## Class E / unverified compatibility routes

`/api/stories*`, `/api/suites*`, several sprint mutation branches predating this change, and legacy process-level routes do not all carry an authoritative project relationship in their storage model. They are not silently reclassified as tenant resources and were not assigned ownership by this batch. Their remaining verification is documented as a gap for later approved work; no H-004 or H-005 implementation was started.

## Consolidated helper contract

[RunnerServer.java:2805](../../src/main/java/com/example/unifiedqarunner/RunnerServer.java:2805) now requires:

1. A nonblank project ID.
2. An authoritative `OrganizationStore` project assignment.
3. An active authenticated session.
4. Active membership in the assigned organization.
5. Explicit membership in the assigned project.
6. The requested domain read/write permission from `TenantContextResolver`.
7. A matching optional client organization ID when supplied; the server-derived assignment remains authoritative.

Absent or quarantined ownership fails closed. No fallback to direct `TeamAuthorization` is used by the consolidated legacy helper.
