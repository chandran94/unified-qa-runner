# Level 4.1.8D D1 — Trust Boundary Map

Identified trust boundaries: **8**.

| ID | Boundary | Untrusted input | Existing validation | Missing/uncertain control | Impact |
|---|---|---|---|---|---|
| TB-01 | HTTP client → auth/session/CSRF/RBAC | Cookies, method, Origin, body | Route auth, same-origin/CSRF and permission helpers | Full route-by-route stale-membership evidence | Unauthorized file operation |
| TB-02 | Resource ID → ownership metadata | IDs, project/org query values | `ProjectOwnershipManager` and parent checks | Complete object/list matrix for every file family | IDOR/cross-tenant disclosure |
| TB-03 | Filename → filesystem path | URL filename, attachment filename | Name sanitization, normalize, generated storage names | Real-path checks for screenshot/video symlinks | File disclosure/deletion |
| TB-04 | Report/run metadata → file stream | Run ID and report directory | TEST_RUN ownership and `ReportStorageSecurity` | Full report/export object matrix | Report disclosure |
| TB-05 | ZIP upload → temporary file → extraction | Multipart body, filename, ZIP entries | 50 MB body cap, normalized ZIP entry containment | Uncompressed bytes, entry count, nesting and symlink limits | Disk/memory exhaustion or escape |
| TB-06 | GitHub URL → external process/filesystem | `repoUrl` | Prefix allowlist and project permission | Bounded clone process/output and egress policy | External execution/resource abuse |
| TB-07 | Configured shared path → discovery/execution | Stored path and selection | Real-path checks for selected import paths | Complete root policy and symlink policy | Cross-project file access |
| TB-08 | Run/plugin output → result/artifact publication | Local process output and paths | Run ownership and bounded execution architecture | Durable file ownership, log redaction, output quotas | Sensitive disclosure/resource abuse |

## End-to-end model

`External client → authentication/session → CSRF/same-origin → RBAC → organization/project ownership → metadata lookup → canonical filesystem path → bounded read/write/delete → response or background publication`.

The ZIP/GitHub path additionally crosses an external source and process/filesystem boundary before catalog registration. No live external source was contacted during D1.

