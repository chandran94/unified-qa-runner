# P1-F12L.0B.6 — Unified media publication

## Result

**IMPLEMENTED / TEST VERIFIED / BACKUP COORDINATED for synthetic captures. LIVE VERIFIED: NO.** No external browser runner, recorder, plugin, or live storage was invoked.

`MediaPublicationService` is the host-managed seam. A caller opens an execution-owned directory under `target/runner-staging`, lets Selenium, Playwright, Cypress, WebdriverIO, a local runner, or a video process finish there, then publishes through `ArtifactPublicationService.publishMedia`. Publication uses `StorageWriteCoordinator` writer `media-artifacts`; coordinator locks are not held during browser execution or recording. Existing URL roots remain `target/screenshots` and `target/videos`, so existing report references remain stable.

| Producer | Boundary found | Result |
|---|---|---|
| Selenium | Only test fixture/hooks and server help text; no host capture callback | Seam available; external integration not live verified |
| Playwright | Existing plugin stages reports, but no production media callback | Seam available; external integration not live verified |
| Cypress | Existing plugin stages JSON; no media callback | Seam available; external integration not live verified |
| WebdriverIO | Existing plugin stages report directories; no media callback | Seam available; external integration not live verified |
| Local runner / existing video process | No implemented producer callback discovered | BLOCKED until producer supplies staging path |
| Plugin media producers | No SDK-breaking change made | BLOCKED if producer writes durable path directly |

Validation rejects incomplete zero-byte files, unsafe names, external paths, symlink escapes, and files over the existing 64 MiB limit. Concurrent publication and staging exclusion are covered by `MediaPublicationServiceTest`. Authentication, tenant ownership, URLs, execution status, and report references were not changed. A failed execution remains failed; publication does not alter status.

Any external producer that ignores the seam can still write directly to `target/screenshots` or `target/videos`; that is the exact remaining uncontrolled-writer limitation. Therefore all-writers backup consistency remains **BLOCKED**, and production readiness remains **NOT APPROVED**.
