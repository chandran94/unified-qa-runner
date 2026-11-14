# Level 4.1.7D Organization Role Verification

## Source review

- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) derives management authorization from the resolved tenant context and serializes `organizationRole` from server-side membership data.
- `membershipToApiMap` serializes `organizationRole` and `membershipStatus` from the persisted `OrganizationMembership`, not from a client-supplied response field.
- [index.html](../src/main/resources/static/index.html) consumes `organizationRole` from the organization response for display and management affordances; this display is not treated as the authorization boundary.
- [TenantContextResolverTest.java](../src/test/java/com/example/unifiedqarunner/TenantContextResolverTest.java) verifies active membership and authoritative project/organization resolution.

## Executed evidence

`mvn -Dtest=OrganizationApiIntegrationTest test`: 3/3 PASS, including organization boundary, role-management denial and CSRF assertions.

No dedicated test in the executed set submits a forged organization role and asserts the returned role, so tamper resistance of the response field remains source-reviewed rather than fully runtime-verified.

| Scenario | Classification |
|---|---|
| Server-authoritative role in management responses | SOURCE-REVIEWED / PARTIALLY VERIFIED |
| Client role cannot elevate authorization | PARTIALLY VERIFIED through server authorization tests; explicit forged-role case NOT RUN |
| Admin/non-admin management access | VERIFIED within organization API test scope |
| Suspended membership has no active privilege | VERIFIED within resolver scope |
| Cross-tenant role data is not disclosed | VERIFIED within organization boundary scope |
| Missing/legacy membership fails closed | SOURCE-REVIEWED; explicit management-route legacy case NOT RUN |

