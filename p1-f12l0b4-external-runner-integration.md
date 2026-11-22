# P1-F12L.0B.4 — External Runner Artifact Integration

## Result

**PARTIALLY VERIFIED.** Newman, Cypress, WebdriverIO, JMeter CLI, and Playwright now stage externally produced reports under `target/runner-staging` and publish them through the existing coordinated publication service after process completion. Robot and host-generated plugin paths were covered in the previous delta.

The live application was not stopped or restarted. PID 23304 and loopback port 8081 were not touched.

## Producer matrix

| Producer | Current result |
|---|---|
| Newman plugin and execution service | **STAGED**, then published after process completion |
| Cypress | **STAGED**, then published after process completion |
| WebdriverIO | **STAGED**, then published after process completion |
| JMeter CLI | **STAGED** `.jtl` and HTML output, then published |
| Playwright Maven tests | **STAGED** Surefire output, then published |
| Robot Framework and host-generated API/Swagger/Pytest outputs | **MANAGED** by the prior delta |
| Gatling Maven process | **BLOCKED**; current invocation does not expose a verified output-folder override |
| Selenium/browser screenshot and video producers | **BLOCKED**; no single controllable producer boundary was found |

`ArtifactPublicationService` validates staging and publication containment, rejects symlinks and traversal, applies bounded file-count and byte limits, and publishes through `StorageWriteCoordinator`. Active staging is excluded from `BackupService`. External processes run before the coordinator is acquired; failed or incomplete staging is discarded.

## Validation

- Maven compile: **PASS**.
- Focused isolated batch before the mandatory recovery test: **47 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**.
- Final batch including recovery: **48 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED** across 49 tests.
- `ArtifactPublicationServiceTest`: 4 PASS.
- `ScreenshotVideoStreamingTest`: 6 PASS, 1 SKIPPED; the skip is the existing Windows symlink-capability case.
- `git diff --check`: **PASS** (existing line-ending warnings only).
- No external runner, CI/CD pipeline, live backup, live email, or live-data mutation was executed.

## Remaining blockers

Gatling and external screenshot/video publication remain outside the verified staging boundary. Full backup consistency and cross-module restore therefore remain **BLOCKED / NOT VERIFIED**. D1-F-001 and D1-F-002 remain OPEN. Production readiness remains **NOT APPROVED**.
