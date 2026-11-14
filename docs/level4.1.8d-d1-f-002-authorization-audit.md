# D1-F-002 Authorization Audit

## Source ordering

In [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java), authentication occurs before dispatch. Both media handlers perform filename validation and regular-file checks, then call `requireArtifactAccess` before `streamFileResponse`. The access helper resolves the authoritative project assignment and invokes tenant/project access resolution with `TEST_EXECUTION` permission.

This ordering means the implementation does not intentionally open or copy media before the authorization call. The source does not show an authorization bypass introduced by the streaming change.

## Evidence

- `ScreenshotVideoStreamingTest`: local Surefire text/XML artifact reports 3/3, including successful media access, unauthenticated denial, traversal rejection, and cross-tenant video denial.
- `TenantRouteIntegrationTest`: local artifact reports 8/8 for related tenant route regressions, but it is not a complete streaming authorization suite.
- These artifacts were not rerun in this audit.

## Coverage limits

Suspended/revoked-member media access, every project/resource relationship variant, symlink escape, and post-revocation behavior are not directly covered by the available media test. Authorization is therefore SOURCE-REVIEWED with PARTIAL historical execution evidence, not a universal runtime proof.

## Verdict

No source-level authorization regression was identified in the reviewed streaming path. Formal closure still depends on resolving the material untested cases and the related real-path containment limitation.

