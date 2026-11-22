# P1-F12L.0B.6 — Recovery regression

## Result

**RESTORE VERIFIED / TEST VERIFIED for the supported isolated graph. LIVE VERIFIED: NO.** The existing `P1F12L0B5CrossModuleRecoveryTest` was rerun and extended only with small synthetic published screenshot/video fixtures.

The graph verified organization/project, sprint/story, test case/defect, wiki, CI/CD metadata, attachment, published report, published screenshot, published video, manifest and SHA-256, staging exclusion, isolated restore, relationship reload, tenant-scoped ownership data, and the existing corruption/missing-artifact checks in `PersistentStorageRecoveryTest`.

Focused delta result: **10 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED**.

Canonical Maven suite result: **261 total, 0 FAIL, 4 ERROR, 20 SKIPPED**. Errors were unrelated existing/environment cases: three `RunnerServerTest` methods attempted occupied port 80, and the intentional Selenium screenshot-demo scenario raised its deliberate missing-element error. The suite did not bind port 8081.

Build compilation: **PASS** (Java 17 release target under Microsoft JDK 25). `git diff --check`: pending final check. Backup consistency is **BLOCKED** because real third-party Gatling compatibility and producer compliance with the media seam were not externally verified. D1-F-001 and D1-F-002 remain **OPEN**. Production readiness remains **NOT APPROVED**.
