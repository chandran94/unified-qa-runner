# Level 4.1.8C-2D — Runner Ownership Matrix

Audit result: **No existing remote/distributed runner resource family was found.** No runner-specific H-005 remediation is applicable in this phase.

## Existing runner-related capabilities

| Capability | Actual source/entry point | Storage/identity | Ownership behavior | Result |
|---|---|---|---|---|
| Local runner status | `GET /api/status`, `RunnerServer.java:574` | In-process `runnerEnabled` flag | Status is local process state; no tenant runner object | Existing local control, not a remote runner resource |
| Local runner enable | `POST /api/enable`, `RunnerServer.java:579` | In-process flag | Browser same-origin gate and existing server authorization path; no runner identity | Not a runner registration/configuration store |
| Run creation | `POST /api/runs`, `RunnerServer.java:712` and `createRun` | In-memory `runs` map and bounded `runExecutor` | Human session plus H-004 `TenantJobContext`; project ownership resolved server-side | H-004 boundary, already remediated |
| Run polling/listing | `GET /api/runs`, `RunnerServer.java:605` | In-memory runs | Filters through `TEST_RUN` ownership | Existing tenant-scoped path |
| Run cancellation | `DELETE /api/runs/{id}`, `RunnerServer.java:729` | In-memory run entry | `TEST_RUN` ownership and H-004 revalidation | Existing tenant-scoped path |
| Local execution worker | `runExecutor`, `RunnerServer.java:121` and `executeRun` | Single-process bounded executor | Carries immutable `TenantJobContext`; no worker claim token | H-004, no distributed claim boundary |
| Result persistence | `persistAutomationExecution`, `RunnerServer.java:2225` | `AutomationExecutionManager` / `qa-runner.json` | C-2A explicit organization/project ownership and H-004 revalidation | C-2A, not a runner registration resource |
| Report/artifact write | `recordRunArtifacts`, `RunnerServer.java:2972` | Shared local paths plus ownership index | H-004 revalidation and artifact ownership index | H-005 artifact boundary; not runner-specific |
| Remote runner registration | No route/class/store found | None | None | Not implemented |
| Runner authentication token | No runner token issuance/verification found | None | None | Not implemented |
| Heartbeat/status mutation | No heartbeat route or runner record found | None | None | Not implemented |
| Job claiming/assignment | No claim/poll route or persisted claim record found | None | None | Not implemented |
| Remote result submission | No runner result callback route found | None | None | Not implemented |
| Runner configuration store | No runner-specific store found | None | None | Not implemented |

## Security conclusion

The application has a local human-triggered, single-process execution engine, not a remote runner platform. There is no existing runner identity, credential, heartbeat, claim, assignment, or result-submission resource that can be safely remediated as one H-005 family. The in-memory queue’s restart and distributed-worker limitations remain documented in [level4.1.8c1-final-report.md](./level4.1.8c1-final-report.md).

Unknown future runner records must not be auto-assigned to an organization or project. A future runner phase will need an explicit service identity and ownership model before implementation.
