# Level 4.1.8A API Authorization Matrix

Legend: `Resolver` means `TenantContextResolver`; `Legacy` means `TeamAuthorization`/`ProjectOwnershipManager`; `Admin-only` is intentional fail-closed access for unresolved ownership; `NV` means not verified by an existing focused test. Every API branch in `RunnerServer.java` was inventoried; related method variants are grouped where they share the same authorization branch.

## Public and authentication APIs

| Method/route | Source | Auth/CSRF | Tenant/project contract | Current result / coverage |
|---|---|---|---|---|
| POST `/api/auth/login` | `RunnerServer.java:237` | Public; same-origin; rate limited | None | 401/429 behavior inspected; auth tests exist |
| POST `/api/auth/register` | `RunnerServer.java:263` | Public; same-origin; rate limited | None | Verification flow tested; external delivery not E2E |
| POST `/api/auth/verification/send`, `/resend`, `/confirm` | `RunnerServer.java:289` | Public; same-origin | Token-bound verification | Unit coverage; no abuse/race audit |
| GET `/api/auth/me` | `RunnerServer.java:298` | Session | User only | Existing auth coverage |
| POST `/api/auth/logout` | `RunnerServer.java:306` | Auth + CSRF | Session | Existing auth coverage |

## Organization and administration

| Method/route | Source | Auth/CSRF | Tenant/project contract | Current result / risk |
|---|---|---|---|---|
| POST/GET `/api/organizations` | `RunnerServer.java:2348`, `2362` | Auth; POST CSRF | Organization creation/list is membership based | API tests: covered |
| POST `/api/organization-invitations/accept` | `RunnerServer.java:2377` | Auth + CSRF | Invitation token and account binding | API/browser tests: covered |
| GET/PATCH `/api/organizations/{orgId}` | `RunnerServer.java:2402`, `2408` | Resolver; PATCH CSRF + owner/admin | Requested org resolved server-side | Covered for tested cases |
| GET `/api/organizations/{orgId}/members` | `RunnerServer.java:2423` | Resolver + owner/admin | Org membership | Covered for tested cases |
| PATCH/DELETE `/api/organizations/{orgId}/members/{membershipId}` | `RunnerServer.java:2428` | Resolver + CSRF + owner/admin | Target membership checked in requested org; last-owner rule in store | Revoke browser flow previously verified; broader session race NV |
| GET/POST `/api/organizations/{orgId}/invitations` | `RunnerServer.java:2451`, `2471` | Resolver + manager; POST CSRF | Org-scoped | Covered for invitations; delivery NV |
| GET/POST `/api/organizations/{orgId}/projects` | `RunnerServer.java:2476`, `2487` | Resolver; POST CSRF + manager | Authoritative project-to-org mapping; list filters project membership | Covered for org tests; full CRUD NV |
| GET `/api/admin/project-ownership/unresolved` | `RunnerServer.java:322` | Platform admin | Quarantine/admin review | Unit/source coverage; operational workflow NV |
| POST `/api/admin/project-ownership/{type}/{id}` | `RunnerServer.java:327` | Platform admin + CSRF | Explicit admin migration only | Unit coverage; production migration controls NV |

## Wiki, database and team APIs

| Method/route | Source | Auth/CSRF | Tenant/project contract | Current result / risk |
|---|---|---|---|---|
| GET/POST `/api/wiki/spaces` | `RunnerServer.java:344`, `350` | Auth; POST CSRF | GET filters owned resources; POST legacy project check | Mixed resolver/legacy; partial |
| GET/PUT/POST archive `/api/wiki/spaces/{id}[/*]` | `RunnerServer.java:359` | Auth; writes CSRF | Owned resource via project ownership | Partial; IDOR tests limited |
| POST `/api/wiki/pages` | `RunnerServer.java:372` | Auth + CSRF | Space ownership then project assignment | Partial |
| GET/PUT/archive/restore `/api/wiki/pages/{id}[/*]` | `RunnerServer.java:381` | Auth; writes CSRF | Owned page | Partial |
| GET/POST `/api/database/connections` | `RunnerServer.java:389`, `394` | Active team member; POST CSRF | **No org/project owner** | **C-001; critical; NV** |
| GET/PUT/DELETE `/api/database/connections/{id}`; POST `test/connect/disconnect` | `RunnerServer.java:397` | View/manage role; write CSRF | **No object ownership** | **C-001; critical; NV** |
| GET/DELETE `/api/database/connection-tests/{id}` | `RunnerServer.java:406` | View/manage role | Test state not tenant-bound | **C-001; NV** |
| GET `/api/team-permissions`, `/api/team-roles` | `RunnerServer.java:414`, `419` | Auth | Project ID is query input; legacy membership model | Partial |
| POST/PUT `/api/team-roles`, GET/POST/PUT `/api/team-members`, DELETE member | `RunnerServer.java:424`–`480` | Admin/CSRF on writes | Team store is global `qa-runner.json`; projectIds are explicit, org linkage absent | Partial; org membership boundary NV |

## Tests, defects and user stories

| Method/route family | Source | Auth/CSRF | Ownership | Current result |
|---|---|---|---|---|
| GET `/api/catalog`, `/api/automation-assets/scenarios`, `/api/automation-executions` | `RunnerServer.java:482`, `498`, `558` | Auth | Filter through mapping/project ownership; unresolved denied except admin | Partial |
| GET/POST/PUT/DELETE `/api/testcase-automation-mappings` | `RunnerServer.java:511`–`554` | Auth; writes CSRF | Test-case ownership for mutation; mapping persistence is legacy | Partial |
| GET `/api/status`, `/api/dependencies`; POST enable/disable/shutdown; POST dependency install | `RunnerServer.java:566`–`744` | Auth; writes same-origin/CSRF | Runner/process-level, not tenant resource | NV for platform-admin boundary |
| GET/POST/PUT/DELETE/archive/restore/history/sync/bulk-delete `/api/testcases*` | `RunnerServer.java:783`–`892` | Auth; writes CSRF | Object ownership for many routes; unresolved stats/sync/bulk are admin-only | Partial; bulk/legacy gaps |
| GET/POST/PUT/link/unlink `/api/defects*` | `RunnerServer.java:894`–`944` | Auth; writes CSRF | Defect project ID/object lookup | Partial; full cross-org matrix NV |
| GET/DELETE `/api/user-stories/attachments/{id}` | `RunnerServer.java:946` | Auth; DELETE CSRF | Attachment project ownership | Partial |
| PUT/DELETE `/api/user-stories/related-links/{id}` | `RunnerServer.java:959` | Auth + CSRF | **No link-to-story ownership check in route** | **H-002; high; NV** |
| GET/POST/PUT/archive/restore `/api/user-stories/projects*` | `RunnerServer.java:979`–`1008` | Auth; writes CSRF/admin on project ops | Resolver list; project writes use admin/legacy paths | Partial |
| GET/POST/PUT/archive/restore `/api/user-stories/epics*` | `RunnerServer.java:1011`–`1035` | Auth; writes CSRF | Project ID/object check varies | Partial |
| GET `/api/user-stories/stories`, links, work-items | `RunnerServer.java:1038`–`1072` | Auth | List filtering by project/tenant varies | Partial |
| POST/PUT/status/activity/comments/attachments/links/test-case links/archive/restore `/api/user-stories/stories*` | `RunnerServer.java:1074`–`1200` | Auth; writes CSRF | Story/project checks in helper paths; nested relation matrix incomplete | Partial |
| GET/POST/PUT/start/complete/story assign/remove `/api/user-stories/sprints*` and GET board | `RunnerServer.java:1202`–`1249` | Auth; writes CSRF | Project legacy/resolver mix | Partial |
| GET/POST/PUT/DELETE `/api/stories*`, `/api/suites*` | `RunnerServer.java:1253`–`1331` | Auth; writes CSRF | Compatibility routes; ownership is not uniformly authoritative | Partial/NV |

## Execution, files, CI/CD and assistant

| Method/route | Source | Auth/CSRF | Ownership | Current result |
|---|---|---|---|---|
| GET/DELETE `/api/screenshots*`, `/api/videos*` | `RunnerServer.java:673`–`702` | Auth; writes CSRF | Individual/list reads filter artifact ownership; collection delete does not | **H-003; high** |
| POST `/api/runs`; GET/DELETE `/api/runs/{id}`; GET `/api/runs/{id}/report` | `RunnerServer.java:704`–`732` | Auth; POST/DELETE CSRF | Test-run ownership; async context not durable | H-004; partial |
| GET/POST/test `/api/cicd/config`, `/api/cicd/test-connection` | `RunnerServer.java:747`–`763` | Auth/project access; writes CSRF | One shared `ciCd` object/projectId | **C-002; critical** |
| GET `/api/cicd/generate`, `/api/cicd/status`; POST `/api/cicd/commit` | `RunnerServer.java:765`–`780` | Auth/project access; commit CSRF | Same singleton; outbound token use | **C-002; critical** |
| POST `/api/ai/ask` | `RunnerServer.java:1369` | Auth + CSRF | No org/project/conversation store | M-001; NV |
| GET/POST `/api/tickets` | `RunnerServer.java:1375` | Auth; POST CSRF | No tenant owner visible in route | Partial/NV |

## Static and compatibility surfaces

| Surface | Source | Contract | Result |
|---|---|---|---|
| `/`, `/index.html`, `/login`, `/register`, `/verify`, `/forgot-password`, `/reset-password` | `RunnerServer.java:177`, `209` | Public UI/auth pages | Public by design |
| Protected pages `/dashboard`, `/testcases`, `/userstories`, `/runner`, `/assets`, `/history`, `/wiki`, `/database`, `/team` | `RunnerServer.java:179`–`220` | Session presence only; API must enforce tenant | Session gate; tenant page boundary NV |
| `/screenshots/{file}`, `/videos/{file}` | `RunnerServer.java:187`–`192`, `2215`–`2237`, `3190`–`3213` | Auth + path defense + artifact ownership | Individual reads protected; no traversal; tests partial |
| WebSocket/SSE/webhook/runner-registration routes | No matching receiver found | Not implemented in inspected server | NOT IMPLEMENTED / not applicable to current code |

## Cross-cutting status

- CSRF exists for state-changing browser routes through `requireAuthenticatedCsrf` or same-origin checks, but a CSRF check is not an ownership check.
- User-supplied `organizationId` is parsed and validated by the resolver for tenant-enabled routes. User-supplied `projectId` is not uniformly routed through that resolver; legacy paths use `TeamAuthorization` directly.
- Lists must filter before serialization. Several modern lists do; shared database and CI/CD lists do not have tenant ownership to filter.
- Expected cross-tenant behavior is generally 403 for authenticated denial, 401 for missing session, and 404 for missing object. The exact distinction is not uniformly tested across all nested resources.

