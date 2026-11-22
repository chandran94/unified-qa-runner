# P1-F12L.0B — Coordinated Backup Integration

## Result

**PARTIALLY VERIFIED — LIVE CONSISTENT BACKUP REMAINS BLOCKED.**

The shared per-root `StorageWriteCoordinator` now protects these authoritative JSON write paths:

| Store | Writer(s) | Coordination | Backup scope |
|---|---|---|---|
| `qa-runner.json` | UserStoryStore, DefectManager | Integrated | Included |
| `.qa-organizations.json` | OrganizationStore | Integrated with its file lock | Included |
| Wiki sections | WikiManager | Integrated | Included in JSON |
| Authentication | AuthUserDirectory | Integrated | Included in JSON; sessions remain memory-only |
| Team/RBAC | TeamMemberManager, TeamRoleManager | Integrated | Included in JSON |
| Attachments/imports/media/reports | RunnerServer and plugin paths | Not all writers integrated | Files are enumerated when present, but consistency is not claimed |
| Test cases, CI/CD, database metadata, ownership, automation history | Additional compatibility-store writers | Not all writers integrated | Backup remains fail-closed |

The coordinator now holds the write lock for the entire snapshot action, so active writes block a snapshot and nested same-thread writes remain reentrant. The backup service refuses a snapshot until every expected writer is registered. No long-running external SQL/network work is placed under the storage lock.

## Tests

`PersistentStorageRecoveryTest`: **6 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**. Coverage includes active-write timeout, nested writes, writer registration failure, corruption, schema rejection, manifest validation and isolated restore.

No live backup was created. PID 23304 and port 8081 remained untouched.

## Remaining blockers

Remaining explicit integration is particularly required for direct attachment upload/delete and durable runner screenshot/video/report publication. The JSON writers for imports, TestCaseManager, database metadata, CI/CD, project ownership, automation mapping/history and shared paths are now coordinated. Until the filesystem writers are integrated, the system correctly reports backup consistency as **BLOCKED**.
