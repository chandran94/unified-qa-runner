# Level 4.1.7D Membership Verification

## Evidence

- UI active-member filtering is implemented in [index.html](../src/main/resources/static/index.html): `memberRows` retains only `membershipStatus === 'ACTIVE'`.
- Server membership transitions and audit calls are implemented in [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) through the organization membership update route, `auditOrganization`, and `membershipToApiMap`.
- Membership persistence is implemented by `OrganizationStore.updateMembership`; the membership identity and timestamps are retained while status changes.
- Active-membership resolution and suspended-membership denial are exercised by `TenantContextResolverTest`, including `staleMembershipAndSuspendedOrganizationFailClosed` and `queuedUserIdRevalidationRejectsRevokedMembership`.
- Organization boundary and CSRF behavior are exercised by `OrganizationApiIntegrationTest`.

## Executed result

`mvn -Dtest=TenantContextResolverTest test`: **6/6 PASS**.

`mvn -Dtest=OrganizationApiIntegrationTest test`: **3/3 PASS**.

These tests prove server-side active-membership and organization boundary behavior within disposable fixtures. They do not prove browser rendering or immutable long-term audit-history retention.

| Control | Classification |
|---|---|
| Active membership visible to authorized management users | PARTIALLY VERIFIED — HTTP management coverage passed; no explicit active-list assertion for every role |
| Revocation transitions to SUSPENDED | PARTIALLY VERIFIED — source and resolver tests support it; no dedicated HTTP revoke assertion |
| SUSPENDED excluded from active-member UI | SOURCE-REVIEWED |
| Historical membership/audit records preserved | SOURCE-REVIEWED / PARTIAL; retention path not independently executed |
| Revoked user loses active permissions | VERIFIED within resolver test scope |
| Other tenant memberships unaffected | VERIFIED within concurrent/organization boundary test scope |
| Unauthorized membership details denied | VERIFIED within `OrganizationApiIntegrationTest` scope |

