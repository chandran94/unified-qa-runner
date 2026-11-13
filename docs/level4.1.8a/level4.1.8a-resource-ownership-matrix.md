# Level 4.1.8A Resource Ownership Matrix

Storage is predominantly a shared `qa-runner.json` compatibility file plus shared project-root artifact directories. `OrganizationStore` is the authoritative organization/project assignment store for resources that have been explicitly migrated. Legacy records are not automatically assigned.

| Resource family | Authoritative owner | Storage / evidence | CRUD/download/background status | Legacy/quarantine and required change |
|---|---|---|---|---|
| Organizations | Organization ID | `OrganizationStore` keys; `OrganizationStore.java:29`–`34` | Resolver-protected CRUD | Persistent and tested; schema migration to DB later |
| Memberships | Organization + user | `organizationMemberships` | Owner/admin CRUD; immediate status recheck | Suspended retained; session/race tests still needed |
| Invitations | Organization + invitation | `organizationInvitations` | Manager create/list; token-bound accept | Retained history; delivery not E2E |
| Projects | Organization + project | `projectOrganizationOwnership` | Org manager create; lists filter membership | Unknown mappings quarantined; immutable assignment |
| User stories / epics / acceptance criteria | Story/epic project | `UserStoryStore` keys | Mixed resolver/legacy nested checks | Derive only explicit project relationship; full CRUD matrix needed |
| Sprints / Kanban boards | Project | `UserStoryStore` sprint keys and board response | Legacy project checks mixed with resolver | No organization field; migration required |
| Test cases / steps / suites | Project via ownership index or story relationship | `TestCaseManager`, `qa-runner.json` | Many object routes use ownership; compatibility routes vary | Unresolved records quarantined/admin-only; add immutable project owner |
| Test-case mappings | Test case project | `automationMappings`, `ProjectOwnershipManager` | Mutation checks test-case ownership; list partial | Relationship-derived; migration and bulk tests required |
| Automation assets | Underlying shared/import path plus mapping/test case | Filesystem discovery, mapping key | Catalog filters via mapping; execution selection scoped indirectly | Assets need durable project/org ownership, not filename identity |
| Test executions / runs | Test case/project | In-memory `RunEntry`, `automationExecutions`, ownership index | Async; retrieval uses run ownership; cancellation uses same | Run without project quarantined; persist org/principal/context |
| Execution history | Test case/run | `automationExecutions`, test history keys | Read paths partial; no full tenant matrix | Add owner to every history row |
| Screenshots / videos | Run/project | `target/screenshots`, `target/videos`; ownership index | Individual read/delete protected; bulk delete gap H-003 | Unassigned files not safely tenant-addressable; quarantine/retention |
| Logs | Run entry in memory / run response | `RunnerServer.RunEntry.logs` | Returned with run response after ownership check | Persisted log ownership and redaction not verified |
| Reports | Run report directory | `target/runner-reports`; run ownership | Report route checks run owner | Durable report owner and cleanup policy needed |
| Report exports | Generated workflow/report responses | `RunnerServer` response streams | CI/CD workflow export project check; no general export matrix | Add export owner and audit events |
| Attachments | Story/project | `userStoryAttachments` + shared attachment directory | Attachment object ownership before serve/delete | Unresolved quarantined; symlink/retention tests needed |
| Defects | `projectId` field | `DefectManager`, `qa-runner.json` | Defect helper resolves project; nested link checks partial | Validate immutable owner on create/update |
| Comments | Story/project | `userStoryActivity` | Story route checks vary; actor audit exists | Add explicit parent ownership at every mutation |
| Wiki spaces/pages | Space/page project via index | `wikiSpaces`, `wikiPages` | Owned-resource checks present | Legacy migration marks unresolved; admin-only until assigned |
| Team members | Project IDs; organization missing | `teamMembers` in `qa-runner.json` | Global team manager/admin operations | Add organization owner and tenant-scoped CRUD |
| Roles/permissions | Platform/team role plus project IDs | `teamRoles`, `TeamAuthorization` | Platform admin and project permission checks | Separate platform admin from tenant roles |
| CI/CD pipelines | Project intended; currently singleton | `ciCd` object in `qa-runner.json` | C-002: one config and token slot | Quarantine legacy singleton; per-org/project secret references |
| Pipeline runs | CI provider repository/project intended | Provider API/cache | Status/sync derives from shared config | Persist project/org on each run; verify webhook alternative |
| Runner registrations | None found | No registration endpoint/model found | Not implemented | Define scoped runner identity and claims in 4.1.8C |
| Runner configuration | Process/global | Server fields and shared config | Platform-level operations | Split platform config from tenant execution config |
| Credentials/secrets | CI/database config stores | Database repository and CI config | C-001/C-002; encryption is local/static | Secret manager, rotation, redaction, per-tenant ACL |
| AI conversations | None | No conversation persistence found | Stateless `/api/ai/ask` | Add owner/retention or explicitly keep stateless and forbid tenant data |
| AI-generated results | Response only | No durable result store found | No tenant persistence | Define data boundary before implementation |
| Audit logs | Auth user audit plus org events | `AuthUserDirectory.recordAudit` and `auditOrganization` | Organization events recorded; coverage uneven | Preserve history; add tenant/resource IDs consistently |
| Notifications | No tenant notification store found | No matching model/endpoint | Not implemented | Define organization/user ownership before adding |
| Integration configurations | Shared config classes/files | CI, remote imports, shared paths, database repository | Mixed legacy/project ownership | Per-org/project records, secret references, audit and retention |

## Ownership conclusions

1. The organization store and resolver establish a sound authoritative pattern for explicitly assigned organizations/projects.
2. The application does not yet apply that pattern to every resource family.
3. Missing ownership is mostly denied or quarantined for legacy objects, which is safer than inference but causes availability gaps.
4. The two confirmed confidentiality/integrity breaks are shared database connections and shared CI/CD configuration/credentials.
5. No legacy record should be assigned to a tenant by display name, path, array order, timestamp, or current active organization.

