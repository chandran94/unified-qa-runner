# Level 4.1.8C-2C — Artifact and Report Ownership Matrix

Audit basis: prior H-005 reports plus targeted source inspection. This batch selects exactly one family.

| Rank | Family | Source/storage | Parent ownership | Access paths | Existing controls and gap | Decision |
|---:|---|---|---|---|---|---|
| 1 | Report files served from completed runs | `ExecutionService.RunResult.reportDirectory`; `target/runner-reports`; `RunnerServer.serveReport` | `TEST_RUN` ownership and immutable H-004 `TenantJobContext` at the route gate | `GET /api/runs/{id}/report` | Run/project authorization exists, but report path containment was lexical and `Files.isRegularFile` followed symlinks; response used `readAllBytes` | **Selected** |
| 2 | Screenshots | `target/screenshots`; `RunnerServer.serveScreenshot`, list/delete routes | `SCREENSHOT` ownership index derived from `TEST_RUN` | `/screenshots/{file}`, `/api/screenshots`, delete routes | Path traversal checks, ownership checks, H-003 bulk filtering; symlink policy and physical namespace remain unverified | Deferred |
| 3 | Videos | `target/videos`; `RunnerServer.serveVideo`, list/delete routes | `VIDEO` ownership index derived from `TEST_RUN` | `/videos/{file}`, `/api/videos`, delete routes | Path traversal checks, ownership checks, H-003 bulk filtering; symlink policy and physical namespace remain unverified | Deferred |
| 4 | Execution logs | In-memory `RunEntry.output` bounded list | H-004 run context | Run status/detail response paths | Bounded in-memory retention and run ownership gate; no restart durability or independent log store | Deferred |
| 5 | Test-run result artifacts | `RunEntry`, `ExecutionService.RunResult`, `automationExecutions` | `TEST_RUN` and C-2A explicit execution owner | `/api/runs*`, automation result route | H-004 revalidation and C-2A ownership; shared report paths remain | Deferred |
| 6 | Attachments | `UserStoryStore`, configured attachment directory | Story/project ownership and `ATTACHMENT` index | User-story attachment download/preview routes | Stored generated filenames and parent path checks; full symlink/retention matrix not verified | Deferred |
| 7 | Report exports | No separate export store/endpoint found in inspected route set | N/A | No independent report-export endpoint found | Not implemented as an independent family | Deferred / N/A |
| 8 | Artifact metadata | `ProjectOwnershipManager` ownership index in `qa-runner.json` | Project ownership derived from run/test-case relationships | List and direct artifact checks | Unassigned legacy metadata is filtered/denied; physical files remain shared | Deferred |
| 9 | Background-generated files | `RunnerServer.recordRunArtifacts`, plugin output paths | H-004 context plus post-run `TEST_RUN` ownership | Async artifact attribution | Ownership revalidation exists; writer namespace is shared and attribution races are not fully tested | Deferred |

## Selected family

The selected family is **report files served from completed runs**. It has the clearest independent security boundary: authorization already occurs at the run route, while the report-file resolver had a concrete symlink escape and unbounded read risk. The fix can be tested without launching a real plugin or migrating all artifact writers.

## Ownership contract

The route must first authorize the run through authoritative `TEST_RUN` project ownership, active organization membership, project membership, and `TEST_EXECUTION` permission. The report resolver then accepts only a regular file whose real path remains beneath the trusted `target/runner-reports` real path. Missing, unassigned, malformed, outside-root, or symlink-escaped reports fail closed. Client filenames do not establish ownership.
