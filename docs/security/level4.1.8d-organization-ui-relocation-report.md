# Organization Management UI Relocation

## Scope

Moved the existing Organization Management controls from the Dashboard context bar into `Settings -> Organization` using the existing frontend handlers and APIs.

No backend Java source, persistence, authentication, RBAC, CSRF, tenant authorization, or application configuration was changed.

## Files changed

- `src/main/resources/static/index.html`
- `src/test/java/com/example/unifiedqarunner/OrganizationBrowserE2ETest.java`
- This report.

Pre-existing untracked runtime files `.qa-organizations.json` and `.qa-organizations.json.lock` were preserved and were not edited by this task.

## Defects found and fixed

1. The first relocation moved the context bar only when the Settings hash was opened. The initialization path now relocates it immediately, so the Dashboard starts with only the compact organization indicator.
2. Ordinary members were sent to admin-only organization-management APIs and saw an error. The page now uses the server-provided membership role to hide Manage and render a read-only organization context for non-admin members. Backend authorization remains authoritative.
3. Existing browser fixture assertions still expected Dashboard controls and the old no-organization status text. The focused fixture now navigates through Settings and asserts the new compact indicator and shared context-bar location.

## Navigation and behavior

- Added a gear-icon Settings item and an indented Organization item targeting `#settings-organization`.
- Added the Organization Settings page and preserved hash routing and authenticated-route handling.
- The existing `tenantContextBar` DOM node is moved into `organizationSettingsSlot` by `ensureOrganizationSettingsLayout()` rather than duplicated. Existing element IDs and action handlers remain intact.
- The Dashboard retains only the compact active-organization indicator.
- Organization switching continues to use the existing tenant generation and abort logic.
- Owner/admin management uses the existing management APIs and `renderOrganizationManagement()` function.
- Ordinary members receive a read-only context view; invite/create actions remain governed by existing server authorization.

## Verification

Compile:

```text
mvn -q -DskipTests compile
PASS
```

Focused isolated browser verification:

```text
mvn -q -Dtest=OrganizationBrowserE2ETest test
4 tests, 0 failures, 0 errors, 0 skipped
PASS
```

Focused organization/security regressions:

```text
mvn -q -Dtest=OrganizationStoreTest,OrganizationApiIntegrationTest,TenantContextResolverTest,TeamAuthorizationTest,CrossTenantHttpSecurityVerificationTest,AuthenticationFoundationTest test
30 tests, 0 failures, 0 errors, 0 skipped
PASS
```

Combined executed test count: 34 unique tests, all passing. The browser fixture uses temporary storage, synthetic users, an ephemeral loopback server, and headless Chrome; it does not use the live application.

Additional static checks:

- Inline JavaScript syntax check with Node `vm.Script`: PASS.
- `git diff --check`: PASS.
- Backend authorization and tenant-isolation source paths were not changed.

## Browser verification status

Isolated browser verification: PASS for Settings navigation, organization management rendering, Dashboard compact context, organization switching, invitation workflow, management actions, and stale-switch behavior covered by `OrganizationBrowserE2ETest`.

Live application browser verification: NOT RUN. The existing application was not restarted or contacted, and port 8081 was untouched.

## Limitations

The focused browser fixture does not cover every possible Back/Forward permutation or every API error variant. Those remain outside this bounded verification run.

## Security status

- H-005: CLOSED; unchanged.
- D1-F-001: OPEN; unchanged.
- D1-F-002: OPEN; unchanged.
- Production readiness: NOT APPROVED.
- Port 8081: untouched.
- Commit/push: not performed.
