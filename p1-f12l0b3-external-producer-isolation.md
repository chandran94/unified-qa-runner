# P1-F12L.0B.3 — External Producer Isolation

## Safety

The live PID 23304, loopback port 8081, live storage, credentials and runtime artifacts were not touched. No live backup, external email, CI/CD pipeline, or production test was run.

## Producer inventory

| Producer | Output | Classification | Coverage |
|---|---|---|---|
| Robot Framework process | `--outputdir` result tree | Regenerable derived | **Staged and atomically published** |
| API Gateway / Swagger host writers | Text/XML report files | Regenerable derived | **Managed publication** |
| Pytest / Robot host log summaries | `output.txt` | Regenerable derived | **Managed publication** |
| Gatling Maven process | `target/gatling-simulations` copied into report tree | Regenerable derived | Not yet redirected through staging |
| Cypress/Newman/JMeter/Playwright and similar external report outputs | Plugin-selected report directories | Regenerable derived | Not yet redirected through staging |
| Selenium/browser screenshots and video capture | `target/screenshots`, `target/videos` or producer-specific paths | Recoverable derived | Deletion coordinated; publication producer remains external |
| Temporary ZIP/tool downloads | `temp`, `target/uploads` | Disposable staging | Excluded from backup scope |

## Implemented staging boundary

`ArtifactPublicationService` provides a bounded host-controlled boundary under `target/runner-staging`. It validates containment, rejects symlinks and traversal, enforces 2,000 files / 64 MiB limits, and publishes only through the existing `StorageWriteCoordinator`. Directory publication uses an atomic directory move where supported and a bounded temporary fallback otherwise. `BackupService` excludes active staging and `.tmp` publication directories.

Robot Framework now writes to staging and publishes its completed result tree only after the process exits and parsing completes. Host-generated API/Swagger/Pytest/Robot artifacts use managed file publication.

## Remaining gap

Several external-process integrations still receive final report directories directly. They remain **BLOCKED** for consistent snapshot claims until each process is redirected to a dedicated staging directory and its completed output is published through the host seam. No production-path rewrite was attempted.

Production readiness remains **NOT APPROVED**.

## P1-F12L.0B.4 delta

Newman, Cypress, WebdriverIO, JMeter CLI, and Playwright output paths are now redirected to unique `target/runner-staging` directories and published through `ArtifactPublicationService` only after bounded process completion. Robot and host-generated plugin paths remain covered by the prior delta. Gatling and external screenshot/video producers remain BLOCKED because their current output paths are not safely controllable from the existing source. The isolated validation batch is **47 PASS, 0 FAIL, 0 ERROR, 1 SKIPPED**. Full backup consistency remains BLOCKED.
