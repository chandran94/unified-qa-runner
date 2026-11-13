# Runtime Validation Report

**Generated**: 2026-11-13
**Target**: `unified-qa-runner`

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Isolated JAR startup | PASS | 0 | Normal Java process, disposable storage, port 51439, `/` 200, protected APIs 401 |
| Integration security tests | PASS | 0 | 13 tests passed, 0 failed, 0 skipped |
| Authenticated browser E2E | PASS | 0 | Four Selenium sessions with isolated server and temporary storage |
| Source hygiene | PASS | 0 | `git diff --check` passed |

**Overall**: PASS for the requested Level 3 verification scope.

## Browser journeys

- Four-role login through the normal login page.
- Project A and Project B list isolation.
- Same-project test-case create/read.
- Cross-project test-case read/update/delete denial.
- Wiki and Team Management isolation.
- Project Manager cross-project denial and Admin-role escalation denial.
- Protected screenshot download denial.
- Missing-CSRF write rejection.
- Logout invalidation and independent-session preservation.

## Test evidence

- Sanitized HTTP evidence: `target/p0.8-live-security-report.json`
- Surefire reports: `target/surefire-reports/`
- Live test: `src/test/java/com/example/unifiedqarunner/LiveTwoAccountAuthorizationTest.java`

## Environment verdict

```text
docker: UNAVAILABLE — no Docker command/daemon was available; no container infrastructure was required.
node: AVAILABLE — v24.14.0.
playwright: UNVERIFIED — PowerShell npx execution was blocked by execution policy; existing Selenium automation passed.
infra-tier: FALLBACK(embedded/local file storage) — disposable local project store.
browser-tier: PRIMARY(Selenium real browser) — existing Maven Selenium dependency.
startup: PASS — normal Java process, stdout URL, HTTP readiness, process/port verification.
integration: PASS — exit_code: 0, passed: 13, failed: 0, skipped: 0.
e2e: PASS — exit_code: 0, passed: 1, failed: 0.
overall: PASS — Level 3 verification evidence is complete for this scope.
```

## Issues found

| Severity | Description | Disposition |
|---|---|---|
| Low | Selenium CDP warning for Chromium major 157 and Selenium 4.27.0 | Test passed; dependency alignment recommended |
| Low | External Google Fonts blocked by CSP | Does not prevent login UI; review separately |
