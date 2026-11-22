# P1-F12L.0C — Full-Suite Error Analysis

The previously reported suite result was 261 total, 0 FAIL, 4 ERROR, and 20 SKIPPED. The four errors were reviewed without rerunning the live-bound suite.

## Exact errors and classification

1. `com.example.doctests.RunCucumberTest`: the intentional screenshot demonstration attempts to locate `#this-element-does-not-exist-12345`, producing Selenium `NoSuchElementException`. Classification: TEST FIXTURE / INTENTIONAL NEGATIVE DEMO. It is not an application defect and was not hidden.
2. `RunnerServerTest.servesTheDashboardAndOnlyAllowsDiscoveredAssetsToBeRun`: `java.net.BindException: Address already in use: bind` at `RunnerServer.java:126`. Classification: ENVIRONMENT / PORT CONFLICT; the test attempts to bind port 80.
3. `RunnerServerTest.dashboardCanCloseTheLocalRunner`: same `BindException` at `RunnerServer.java:126`. Classification: ENVIRONMENT / PORT CONFLICT; port 80 conflict.
4. `RunnerServerTest.rejectsRunRequestsWithoutTheDashboardOrigin`: same `BindException` at `RunnerServer.java:126`. Classification: ENVIRONMENT / PORT CONFLICT; port 80 conflict.

No test was disabled or modified to conceal these errors. The isolated release validation avoided port 80 and port 8081.

## Focused regression result

The isolated release snapshot ran:

`MediaPublicationServiceTest`, `GatlingPluginSafetyTest`, and `P1F12L0B5CrossModuleRecoveryTest`.

Result: 6 tests, 6 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED; Maven BUILD SUCCESS.

The recovery test verified the existing cross-module graph, including synthetic screenshot/video fixtures, manifest and SHA-256 validation, staging exclusion, isolated restore, tenant ownership, and corrupted-backup rejection. This is RESTORE VERIFIED for the supported fixture path.

## Remaining limitations

- Real external Gatling, browser-farm, CI/CD, and plugin producer execution was not run.
- D1-F-001 and D1-F-002 remain OPEN.
- Backup consistency remains BLOCKED until real external producer compliance is independently verified.
- Production readiness remains NOT APPROVED.
