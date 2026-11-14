# Level 4.1.8D D1 — File-Related HTTP Endpoint Inventory

Counted from current `RunnerServer.java` route branches and root handlers: **30 scoped file/import/artifact-related endpoint branches/patterns**. This is an inventory count, not a security-pass count.

| # | Method and route | Handler/source | Auth/CSRF/RBAC and storage | D1 assessment |
|---:|---|---|---|---|
| 1 | GET `/api/catalog` | `handleApi` around 494; catalog helpers | Auth; project-filtered asset metadata; shared paths/imports | Server filtering; verify metadata leakage. |
| 2 | GET `/api/automation-assets/scenarios` | around 510 | Auth; TEST_EXECUTION; project ownership through mappings | Shared filesystem discovery. |
| 3 | GET `/api/shared-paths` | around 615 | Auth; ownership-filtered list | Names/paths are returned; path disclosure risk. |
| 4 | POST `/api/shared-paths` | around 622 | Same-origin/CSRF; TEST_EXECUTION; project ownership | Accepts configured path; path validation requires D2/D3 review. |
| 5 | DELETE `/api/shared-paths/{name}` | around 627 | Same-origin/CSRF; TEST_EXECUTION; owner check | Delete authorization present; verify physical deletion. |
| 6 | POST `/api/shared-paths/{name}/scan` | around 638 | Same-origin/CSRF; TEST_EXECUTION; owner check | Reads configured directory. |
| 7 | GET `/api/imports` | around 647 | Auth; ownership-filtered list | Path removed from response; source metadata remains. |
| 8 | POST `/api/imports/github` | around 655; `importFromGitHub` | Same-origin/CSRF; project TEST_EXECUTION | Starts external `git clone`; no live call made in D1. |
| 9 | POST `/api/imports/zip` | around 660; `importFromZip` | Same-origin/CSRF; project TEST_EXECUTION; 50 MB request cap | ZIP extraction has no decompressed-size/entry-count cap. D1-F-001. |
| 10 | DELETE `/api/imports/{id}` | around 665 | Same-origin/CSRF; owner check | Removes import metadata/directory. |
| 11 | POST `/api/imports/{id}/scan` | around 677 | Same-origin/CSRF; owner check | Real-path import-root check in `RemoteImportConfig`. |
| 12 | GET `/api/screenshots` | around 685; `listScreenshots` | Auth; artifact visibility filter; TEST_EXECUTION | Metadata list filtered server-side. |
| 13 | DELETE `/api/screenshots` | around 689; `deleteAllScreenshots` | Same-origin/CSRF; per-file artifact visibility | Bulk delete uses visible files only; real-path/symlink review remains. |
| 14 | DELETE `/api/screenshots/{file}` | around 694; `deleteScreenshot` | Same-origin/CSRF; artifact owner | Lexical containment and regular-file check. |
| 15 | GET `/screenshots/{file}` | root handler; `serveScreenshot` around 2338 | Auth; artifact owner; no mutation CSRF | Lexical containment; reads whole file; symlink potential. |
| 16 | GET `/api/videos` | around 700; `listVideos` | Auth; artifact visibility filter | Metadata list filtered server-side. |
| 17 | DELETE `/api/videos` | around 704; `deleteAllVideos` | Same-origin/CSRF; per-file artifact visibility | Bulk delete uses visible files only. |
| 18 | DELETE `/api/videos/{file}` | around 709; `deleteVideo` | Same-origin/CSRF; artifact owner | Lexical containment and regular-file check. |
| 19 | GET `/videos/{file}` | root handler; `serveVideo` around 3445 | Auth; artifact owner; no mutation CSRF | Lexical containment; whole-file read; symlink potential. |
| 20 | GET `/api/runs` | around 608 | Auth; TEST_RUN ownership filter | Run metadata/log response boundary. |
| 21 | POST `/api/runs` | around 715; `createRun` | Same-origin/CSRF; TEST_EXECUTION; project ownership | Creates reports/artifacts under project root. |
| 22 | GET `/api/runs/{id}` | around 725 | Auth; TEST_RUN ownership | Returns run/result/log metadata. |
| 23 | DELETE `/api/runs/{id}` | around 732 | Same-origin/CSRF; TEST_RUN ownership | Cancels run; result lifecycle requires D3/D5 verification. |
| 24 | GET `/api/runs/{id}/report` | around 739; `serveReport` | Auth; TEST_RUN ownership; real-root report containment | Streams report with CSP/no-store. |
| 25 | GET `/api/user-stories/attachments/{id}` | around 958 | Auth; ATTACHMENT ownership; USER_STORIES | Stored-name lookup; bounded stream. |
| 26 | DELETE `/api/user-stories/attachments/{id}` | around 964 | Same-origin/CSRF; ATTACHMENT ownership; USER_STORIES | Metadata and file deletion. |
| 27 | GET `/api/user-stories/stories/{id}/attachments` | around 1138 | Auth; story parent route | Lists attachment metadata; parent filtering requires verification. |
| 28 | POST `/api/user-stories/stories/{id}/attachments` | around 1144; upload helper | Same-origin/CSRF; story project ownership; 10 MB cap | Extension/content checks and generated names present. |
| 29 | GET `/api/cicd/generate` | around 776 | Auth/project CI/CD permission | Generates downloadable workflow content; export ownership review. |
| 30 | GET `/api/dependencies` | around 745 | Auth/platform operation | Reports dependency/tool state; not a tenant artifact store. |

Planned plugin output/log APIs, webhook receivers, and remote runner endpoints were not found and are NOT IMPLEMENTED.

