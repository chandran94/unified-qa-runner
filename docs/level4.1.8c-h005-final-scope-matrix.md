# Level 4.1.8C H-005 Final Scope Matrix

## Evidence basis

Original evidence is immutable under [`docs/level4.1.8a/`](./level4.1.8a/). The stable denominator is the 14 implemented H-005 families used by the recovered C-3 matrix. Later C-2G pipeline-cache work is mapped as a related post-audit boundary and does not silently alter that denominator.

| # | Original family/reference | Current endpoint/store | Implementation | Prior work | Security verification | Final classification / residual |
|---:|---|---|---|---|---|---|
| 1 | Database connections / C-001 | `/api/database/connections*`; `DatabaseConnectionRepository` | IMPLEMENTED | C-001 | Tenant route HTTP tests; targeted regressions | VERIFIED WITHIN TESTED SCOPE; mutation/connection-test branches remain less exercised |
| 2 | CI/CD configuration / C-002 | `/api/cicd/config`, test-connection; `CiCdConfig` | IMPLEMENTED | C-002 | Scoped HTTP tests and token-redaction regressions | VERIFIED WITHIN TESTED SCOPE; live provider execution intentionally not run |
| 3 | Test-case/pipeline result import | `/api/testcases/sync` | IMPLEMENTED | C-2B | Scoped import HTTP tests | VERIFIED WITHIN TESTED SCOPE |
| 4 | Automation executions / C-2A | `/api/automation-executions`; `AutomationExecutionManager` | IMPLEMENTED | C-2A, C-3B restart test | Two-tenant HTTP filtering plus restart ownership test | VERIFIED WITHIN TESTED SCOPE; durable queue recovery is separate and absent |
| 5 | Local runs/execution metadata / H-004 | `/api/runs`, `/api/runs/{id}`; in-memory `RunEntry` | IMPLEMENTED, in-memory | H-004/C-1 | Context and selected route regressions; prior HTTP matrix partial | PARTIALLY VERIFIED; complete object-ID/revocation/retry HTTP matrix remains evidence gap |
| 6 | Report files / C-2C | `/api/runs/{id}/report`; report storage helper | IMPLEMENTED | C-2C | Path/real-path/streaming tests and report regressions | PARTIALLY VERIFIED; no separate report metadata object API exists |
| 7 | Screenshots/videos / H-003 | `/api/screenshots*`, `/api/videos*`, file routes | IMPLEMENTED | H-003, C-2C | Bulk deletion and artifact ownership regressions | VERIFIED WITHIN TESTED SCOPE; video-specific HTTP breadth remains limited |
| 8 | Shared paths/catalog / C-2F | `/api/catalog`, shared-path discovery | IMPLEMENTED | C-2F | Two-tenant catalog HTTP test | VERIFIED WITHIN TESTED SCOPE |
| 9 | GitHub/ZIP imports / C-2E | `/api/imports*`; `RemoteImportConfig` | IMPLEMENTED | C-2E | Tenant filtering and trusted-root tests | VERIFIED WITHIN TESTED SCOPE; live provider not run |
| 10 | Test cases/mappings/discovered assets | `/api/testcases*`, mappings, catalog | IMPLEMENTED | H-001/H-002/C-2F | Representative route and ownership tests | PARTIALLY VERIFIED; complete CRUD/search/mapping HTTP matrix not executed |
| 11 | User stories/epics/sprints/boards | `/api/user-stories/*`; `UserStoryStore` | IMPLEMENTED | H-001/H-002/H-003 | Tenant route tests and attachment test | PARTIALLY VERIFIED; not every CRUD branch was exercised |
| 12 | Wiki spaces/pages | `/api/wiki/*`; `WikiManager` | IMPLEMENTED | H-001 scope | Organization/list route evidence | PARTIALLY VERIFIED; page-object and mutation HTTP matrix incomplete |
| 13 | Attachments | Story attachment routes; `userStoryAttachments` and `.qa-attachments` | IMPLEMENTED | C-3B | `RemainingH005HttpSecurityVerificationTest`, 1 focused test | VERIFIED WITHIN TESTED SCOPE for implemented user-story operations |
| 14 | Logs/plugin results/history | `RunEntry.output`; plugin report directories | PARTIALLY IMPLEMENTED | H-004/C-3B assessment | No separate output/log HTTP resource found | NOT APPLICABLE for separate API; future plugin/log persistence remains a requirement |

## Related post-audit boundaries

Pipeline status/auto-sync cache (`PipelineStatusService`, `/api/cicd/status` and sync branches) was added after the original 14-family matrix and was tested in C-2G. Remote runner registration/webhooks have no implementation and are NOT APPLICABLE, not verified features.

## Evidence rule

“VERIFIED WITHIN TESTED SCOPE” means source and applicable tests support the stated boundary; it does not mean every operation or production deployment scenario is proven secure.

