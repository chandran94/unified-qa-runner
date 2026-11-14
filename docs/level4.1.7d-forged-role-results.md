# Level 4.1.7D Forged Organization Role Results

## Test evidence

New test class: `src/test/java/com/example/unifiedqarunner/Level417DCompletionTest.java`.

Test `organizationRoleIsServerAuthoritativeAndCannotBeForged`:

- Authenticates a synthetic Organization A MEMBER.
- Reads `GET /api/organizations/{organizationId}` and asserts the response contains `organizationRole: MEMBER`.
- Sends a forged `organizationRole: OWNER` field to the organization PATCH route.
- Asserts the server denies the non-manager with HTTP 403 and does not return an authorization-success response.
- Confirms the same user remains unable to list organization members: HTTP 403.

Result: **PASS**, 1 test / 4 assertions.

The route source derives the response role from `TenantContext.organizationRole()` and restricts organization management through server-side role checks. No client role field is accepted by the organization update contract.

Unverified: a separate suspended-role response test and explicit legacy-membership route case were not added; resolver coverage confirms suspended users fail closed.

