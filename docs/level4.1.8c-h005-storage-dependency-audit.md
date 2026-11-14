# Level 4.1.8C H-005 Storage Dependency Audit

## Scope

Gate A read-only audit for the final blocked test/mapping CRUD family. No HTTP server was started, no test mutation was executed, and no production source was changed.

Effective process working directory:

`C:\Users\elayachandran\IdeaProjects\Stock_Market_AI_Analyser_no-credentials_20261107_213042\unified-qa-runner`

The relative file resolved from that directory is:

`C:\Users\elayachandran\IdeaProjects\Stock_Market_AI_Analyser_no-credentials_20261107_213042\unified-qa-runner\qa-runner.json`

It exists and was observed at 70,223 bytes during the read-only gate.

## Store table

| Store | Read/write path | Path resolution | Override supported? | Isolation proven? |
|---|---|---|---|---|
| `TestCaseManager` | Test-case list/create/read/update/delete and history methods | `Path.of("qa-runner.json")` in multiple methods, including lines 64, 95, 505, 536, 632, 666, 730, 749 | No constructor root or supported property; no-arg manager is created by `RunnerServer` | **NO** |
| `AutomationMappingManager` | Mapping list/link/unlink methods | `Path.of("qa-runner.json")` at lines 76 and 93 | No constructor root or supported property; no-arg manager is created by `RunnerServer` | **NO** |
| `RunnerServer.testCaseManager` | `/api/testcases*` route dependency | `new TestCaseManager()` at `RunnerServer.java:65` | No | **NO** |
| `RunnerServer.automationMappingManager` | `/api/testcase-automation-mappings` dependency | `new AutomationMappingManager()` at `RunnerServer.java:67` | No | **NO** |
| `SharedPathConfig` | Catalog/automation asset discovery used by mappings | Constructor-injected `projectRoot`, `projectRoot/qa-runner.json` | Yes, through `RunnerServer(Path,...)` | Yes, source-level |
| `ProjectOwnershipManager` | Test-case/mapping ownership index | Constructor-injected `projectRoot/qa-runner.json` | Yes, through `RunnerServer(Path,...)` | Yes, source-level |
| `UserStoryStore` | Project/story parent data | Constructor-injected absolute normalized root path | Yes | Yes, source-level |
| `OrganizationStore` | Organizations, memberships, project assignment | Constructor-injected root/data path | Yes | Yes, source-level |
| `TeamMemberManager` / `TeamRoleManager` | Project membership and role data | Constructor-injected `projectRoot/qa-runner.json` | Yes | Yes, source-level |
| `AuthUserDirectory` | Users and account state | Constructor-injected `projectRoot/qa-runner.json` | Yes | Yes, source-level |
| `AuthSessionManager` / CSRF | Session and CSRF state | In-memory process state | Not file-backed | Test-process isolated, but dependent stores remain unsafe |
| `RunnerServer` run state | Active runs and output | In-memory maps/lists | Constructor-independent | Not relevant to CRUD gate |

## Gate A conclusion

An ephemeral port would not isolate the relative stores. A temporary `RunnerServer` root would isolate the constructor-injected stores while the two no-argument managers would continue reading/writing the project working directory. Changing `user.dir` inside the Maven JVM is not accepted as proof because it is process-global and does not establish that every cached/static path follows it.

