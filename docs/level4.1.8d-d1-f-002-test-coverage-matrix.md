# D1-F-002 Test Coverage Matrix

Evidence source: [ScreenshotVideoStreamingTest.java](../src/test/java/com/example/unifiedqarunner/ScreenshotVideoStreamingTest.java), [TenantRouteIntegrationTest.java](../src/test/java/com/example/unifiedqarunner/TenantRouteIntegrationTest.java), and local Surefire artifacts. No tests were executed during this audit.

| Scenario | Classification | Evidence |
|---|---|---|
| Screenshot success | EXECUTED EVIDENCE AVAILABLE | Test source and local 3/3 artifact |
| Video success | EXECUTED EVIDENCE AVAILABLE | Test source and local 3/3 artifact |
| Byte-for-byte integrity | EXECUTED EVIDENCE AVAILABLE | Test assertions compare body bytes |
| Content-Type | EXECUTED EVIDENCE AVAILABLE | PNG/MP4 assertions |
| Content-Disposition | NOT COVERED / not emitted | No header in handlers |
| Content-Length | EXECUTED EVIDENCE AVAILABLE | Exact length assertions |
| Missing file | EXECUTED EVIDENCE AVAILABLE | 404 assertion |
| Unauthenticated user | EXECUTED EVIDENCE AVAILABLE | 401 assertion |
| Cross-tenant user | EXECUTED EVIDENCE AVAILABLE | 403 video assertion |
| Suspended/revoked user | NOT COVERED | No media-specific scenario |
| Traversal rejection | EXECUTED EVIDENCE AVAILABLE, limited | Status-only rejection assertion |
| Symlink escape | NOT COVERED | No test; source lacks real-path check |
| Empty file | NOT COVERED | No scenario |
| File larger than buffer | EXECUTED EVIDENCE AVAILABLE | 48 KiB+ deterministic fixtures |
| Client disconnect | NOT COVERED | No scenario |
| Stream cleanup on failure | SOURCE-REVIEWED ONLY | try-with-resources; no failure test |
| Existing download regressions | EXECUTED EVIDENCE AVAILABLE, indirect | TenantRoute artifact 8/8; not a streaming test |
| Real-data isolation | EXECUTED EVIDENCE AVAILABLE | Temp-root fixture construction in test source |
| Port isolation | EXECUTED EVIDENCE AVAILABLE | `RunnerServer(root, 0, ...)` fixture and no live endpoint use |

The local result artifacts report `ScreenshotVideoStreamingTest` 3/3 and `TenantRouteIntegrationTest` 8/8. They are historical/local evidence, not newly executed results. The combined reported unique count is 11/11, but this audit does not independently reproduce it.

