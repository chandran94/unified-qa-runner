# P1-F12L.0C — Browser E2E

Status: PARTIAL ISOLATED BROWSER VERIFICATION. The release artifact and browser session were isolated; production readiness is NOT APPROVED.

## Browser results

One browser worker used the isolated URL `http://127.0.0.1:63721/`.

- PASS: dashboard rendered; navigation and AI Assistant content were present for Admin.
- PASS: settings/organization, test-case, user-story, and Wiki pages rendered by direct protected-hash navigation.
- PASS: synthetic Admin authentication through the isolated browser session returned 200.
- PASS: synthetic story and test case were created in isolated storage and read back through browser-context requests; story read returned 200 and persisted after navigation.
- PASS: wrong-tenant story read returned 403.
- PASS: Project Manager project mutation returned 403.
- PASS: Regular User story mutation returned 403.
- PASS: authenticated logout returned 200; subsequent `/api/auth/me` returned 401.
- PASS: no request used port 8081 and no live page was interacted with.
- BLOCKED: the shared browser harness timed out on several normal UI click actions even when elements had visible bounding boxes. Login and fixture setup were therefore completed through same-origin browser-context requests, followed by rendered-page verification.
- BLOCKED: full UI create/edit flows for every requested module were not claimed because the app's project context did not expose the synthetic project in the organization-scoped project list, despite direct project/story/test-case authorization succeeding.

## Security observations

- Expected 401/403 responses were observed for unauthenticated and unauthorized requests; these are not failures.
- The browser reported that the external Google Fonts stylesheet was blocked by the application's CSP. This is a security-positive block but a visual dependency limitation.
- No HTTP 500 response was observed during the isolated checks.
- No production credentials, external APIs, pipelines, SMTP/SMS providers, or real runners were contacted.

## Counts

Browser assertions executed: 14 PASS, 0 FAIL, 0 ERROR, 3 BLOCKED. The blocked items are explicitly retained as blocked, not converted to pass. The settings assertion used the rendered `SETTINGS` heading and `Organization` content; capitalization was normalized in the report.

Classification: IMPLEMENTED / TEST VERIFIED for isolated startup and security smoke; RESTORE VERIFIED is covered by the focused recovery test, not by this browser session; LIVE VERIFIED is limited to the unchanged live health check.
