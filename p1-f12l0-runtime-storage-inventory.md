# P1-F12L.0 Runtime Storage Inventory

## Runtime identity

- PID: 23304
- Executable: Microsoft JDK 25.0.5.21
- Main class: `com.example.unifiedqarunner.Main`
- Listener: `127.0.0.1:8081`, owned by PID 23304
- Observed storage root: task-owned disposable Phase 3E temp root outside the workspace
- HTTP health: 200

The runtime selects its project root from the first valid command-line path, then `qa.runner.projectRoot`, `QA_RUNNER_PROJECT_ROOT`, the working directory, or the code-source location. The observed process uses the disposable root as its first argument. `java.io.tmpdir` is used for a separate temporary Git clone directory, not as the primary project-root selector.

## Store inventory

| Domain | Observed implementation/path | Classification | Preservation notes |
|---|---|---|---|
| Users/authentication/audit | `qa-runner.json` keys `authUsers`, `authAuditLog` | PERSISTENT | Password hashes and account records are file-backed; values were not copied into this report. |
| Projects/stories/sprints/links/activity | `qa-runner.json` keys `userStoryProjects`, `userStories`, `userStorySprints`, `userStoryTestCaseLinks`, `userStoryCreators`, `userStoryAttachments`, `userStoryRelatedLinks`, `userStoryActivity` | PERSISTENT | UserStoryStore uses temporary-file replacement; relationships remain in the same compatibility file. |
| Test cases/test suites | `qa-runner.json` compatibility store, including `testSuites` | PERSISTENT | Shared-file updates can overlap with other managers without a cross-manager transaction. |
| Defects | `qa-runner.json` | PERSISTENT | Shared compatibility file. |
| Team roles/memberships | `qa-runner.json` keys including `teamRoles` and related manager data | PERSISTENT | Shared compatibility file. |
| Organization/membership/invitations/project ownership | `.qa-organizations.json` plus `.qa-organizations.json.bak` and `.qa-organizations.json.lock` | PERSISTENT | Separate schema-versioned store with lock and prior-write backup when data exists. Current runtime root had the lock but no organization data or backup file. |
| Wiki | `qa-runner.json` wiki keys | PERSISTENT | Wiki writes use temp-file replacement and atomic move when supported. |
| Attachments | `<root>/.qa-attachments` | PERSISTENT FILES | Directory was absent in the observed runtime root; future uploads are separate files plus metadata in `qa-runner.json`. |
| Screenshots/videos | `<root>/target/screenshots`, `<root>/target/videos` | TEMPORARY/PROJECT-ROOT FILES | No target directory existed in the observed root. Files are not automatically preserved by a restart plan unless explicitly included. |
| Runner reports/execution history | `<root>/target/runner-reports` and execution metadata in `qa-runner.json` | PERSISTENT FILES / UNKNOWN RETENTION | Report directories are project-root files; cleanup policy is operation-dependent. |
| CI/CD configuration | `qa-runner.json` | PERSISTENT | No external pipeline was invoked. |
| Imported repositories | `<root>/imports` | PERSISTENT FILES | Directory exists and is separate from JSON stores. |
| Tool installations/temp downloads | `<root>/apache-maven`, `<root>/apache-jmeter-5.6.3`, `<root>/temp` | PERSISTENT/OPERATIONAL | Not application business data; must not be confused with a backup. |
| Sessions | `AuthSessionManager` memory | TEMPORARY | Restart invalidates sessions; cookies must be re-authenticated. |
| SMTP/OTP challenges | In-memory manager/provider state | TEMPORARY | Restart can invalidate pending challenges; no secrets were inspected. |
| Database connections | `qa-runner.json` configuration | PERSISTENT CONFIGURATION | External database execution was not invoked. |

## Lifecycle findings

`Main` uses try-with-resources around `RunnerServer`; normal close stops the HTTP server and executors. `RunnerServer.close()` does not recursively delete the project root or its business stores. There is no evidence of a JVM shutdown hook that creates a consistent snapshot. A forced termination or abrupt machine failure therefore cannot be assumed to flush or preserve the latest writes.

OrganizationStore uses a JVM/file lock and temp-file replacement with a `.bak` prior-write copy. UserStoryStore, authentication, ownership, and other compatibility managers use temp-file replacement in several paths, but the shared `qa-runner.json` is not updated transactionally across managers. Atomic replacement also does not by itself prove durable-on-disk persistence without an fsync protocol.

## Risk summary

| Risk | Rating | Reason |
|---|---|---|
| Session invalidation on restart | LOW | Expected in-memory behavior; users can sign in again. |
| Partial JSON write | LOW/MEDIUM | Temp replacement reduces truncation risk, but fallback non-atomic moves exist. |
| Cross-store inconsistent snapshot | HIGH | Multiple files/stores and shared JSON writers have no coordinated quiescence or transaction. |
| Lost latest write during live copy | HIGH | Copying while writers are active is not a consistent snapshot. |
| Schema incompatibility | MEDIUM/UNKNOWN | Version checks exist, but live runtime identity and rollback artifact are not proven. |
| Attachment/report loss | MEDIUM | Files are separate from metadata and were not covered by a verified snapshot. |
| Restart data reset | LOW based on source | Startup does not intentionally delete the project root, but full recovery is not proven. |

## Conclusion

Storage location is identified, but data-preserving restart readiness is not established. A consistent snapshot requires a supported quiescence/maintenance procedure or filesystem snapshot while writes are stopped; neither was available without interrupting PID 23304.
