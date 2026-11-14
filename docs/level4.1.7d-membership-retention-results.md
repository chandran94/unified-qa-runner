# Level 4.1.7D Membership Retention Results

## Test evidence

New test class: `src/test/java/com/example/unifiedqarunner/Level417DCompletionTest.java`.

Test `revocationPersistsSuspendedStatusAndAuditHistory`:

- Creates synthetic Organization A, owner and member in a temporary store.
- Revokes the member through `DELETE /api/organizations/{organizationId}/members/{membershipId}`.
- Asserts HTTP 200 and `SUSPENDED` response state.
- Reloads `OrganizationStore` and asserts persisted `MembershipStatus.SUSPENDED`.
- Reads only the disposable JSON and asserts an audit action beginning `MEMBERSHIP_REVOKED:` remains present.
- Confirms a separate synthetic membership remains ACTIVE.
- Confirms the revoked member receives HTTP 403 for membership management.

Result: **PASS**.

`mvn -Dtest=Level417DCompletionTest test`: 3/3 PASS, including this test.

Existing `TenantContextResolverTest`: 6/6 PASS, including suspended membership and queued-user revalidation denial.

## Classification

- Revocation persistence: VERIFIED within disposable HTTP/store scope.
- Audit record preservation: VERIFIED for the persisted `authAuditLog` event created by the route.
- Active permission loss: VERIFIED within route/resolver scope.
- Browser active-member filtering: not tested here; see frontend report.

