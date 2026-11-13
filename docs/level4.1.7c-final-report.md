# Level 4.1.7C final report

## Result

**Level 4.1.7: BLOCKED.** Production readiness remains **NOT APPROVED**.

No existing 8081 process was restarted or terminated. The isolated server used an ephemeral loopback port and disposable temporary storage. Cleanup completed and port 8081 had no listener at the end of validation.

## Legacy 401 diagnosis and repair

The three legacy `RunnerServerTest` failures were obsolete anonymous-access assumptions:

- `GET /api/catalog` is authenticated and its visible asset catalog also requires a permitted team identity.
- `POST /api/runs` is authenticated, CSRF-protected, and same-origin protected; the test also used an outdated request contract that omitted `testCaseId`.
- `POST /api/shutdown` is authenticated, CSRF-protected, and same-origin protected.

The test now creates a disposable verified user, logs in through `/api/auth/login`, sends the session cookie and CSRF token, and provisions a disposable Admin team identity for catalog visibility. Production security was not weakened. `RunnerServerTest`: **3/3 PASS**.

## Browser evidence

Selenium used Chromium **157.0.8103.78** with Selenium **4.27.0**. Selenium emitted the expected CDP 157 compatibility warning; no browser-security flags were disabled and the passing invitation flow was reliable.

Executed against the isolated loopback server:

- Invitation acceptance through the normal login/UI flow: **1/1 PASS**. The invitation token stayed in memory, was submitted only through the normal UI, and was absent from page source afterward.
- Owner management flow: **FAIL** at member revocation. The UI rendered four authorized member rows and accepted the role-update interaction, but the subsequent revoke action did not reduce the server-rendered member rows from four to three.
- Multi-organization switching/race flow: **FAIL**. The UI rendered organizations A and B, but the authorized user-story project request returned `403 You are not authorized for this project` for Organization A. The test therefore did not claim stale-response success.

The race harness and frontend cancellation path are present, including clearing tenant state before switching and ignoring `AbortError`; the end-to-end proof remains incomplete until the authorization mismatch is resolved.

## Validation counts

- Maven compile: **PASS**.
- Organization API + tenant route tests: **8/8 PASS** (3 organization API, 2 tenant route, 3 repaired server tests).
- Organization browser suite: **1/3 PASS, 2/3 FAIL**.
- Level 3 regression selection: **56/56 PASS**.
- `git diff --check`: **PASS** (only normal Windows line-ending warnings were reported).
- Port 8081 final check: **no listener**.

## Files modified

- `src/test/java/com/example/unifiedqarunner/RunnerServerTest.java`
- `src/test/java/com/example/unifiedqarunner/OrganizationBrowserE2ETest.java`
- `src/main/java/com/example/unifiedqarunner/RunnerServer.java`
- `src/main/resources/static/index.html`

The server change allows query parameters for the existing user-story epic route. The frontend changes scope user-story reads to the active organization/project and suppress stale-request `AbortError` notifications. These changes require a fresh browser pass after the remaining authorization and revoke issues are corrected.

## Remaining blockers

1. Determine why the authorized multi-organization fixture receives 403 for its organization-owned user-story project despite active organization membership and project assignment.
2. Determine why the browser revoke interaction does not persist/remove the selected membership.
3. Re-run the complete organization browser matrix, including organization-specific test cases, wiki, team management, and user-story project data, after those blockers are fixed.

Level 4.1.8 and PostgreSQL work were not started.
