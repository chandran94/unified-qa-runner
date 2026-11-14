# Level 4.1.8C-3B Remaining Coverage Plan

Verification only; no production Java, route, schema, live server, or port 8081 was changed.

## Isolation

- `RunnerServer` used a unique `Files.createTempDirectory` root and port `0`.
- The server bound to loopback on an OS-assigned ephemeral port.
- Two disposable users, organizations, projects, stories, memberships, and execution records were used.
- Separate HTTP cookies and CSRF tokens were used.
- No external provider, database, browser, Docker, PostgreSQL, or live port was used.
- Cleanup deleted only the test-owned temporary root.

## Coverage decisions

| Area | Source result | Verification |
|---|---|---|
| Report object IDs | No separate report metadata/list/search object API; `/api/runs/{id}/report` is file serving covered by C-2C | Metadata-object HTTP verification not applicable; retain C-2C evidence |
| Attachments | User-story attachment upload/read/delete routes exist | Same-project access, cross-tenant denial, forged parent denial, CSRF, delete, and path disclosure |
| Plugin outputs/logs | No separate plugin output/log store or HTTP endpoint; run output is in-memory | Not implemented/not applicable; no untrusted plugins executed |
| Restart ownership | `automationExecutions` persist in `qa-runner.json`; queue is in-memory | Persisted ownership across isolated restart; durable queue recovery separately recorded as not implemented |

## Commands

`mvn -q -DskipTests compile`

`mvn -q -Dtest=RemainingH005HttpSecurityVerificationTest test`

Targeted regression command is recorded in the final report.

