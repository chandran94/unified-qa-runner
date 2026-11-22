# Project and TCM Creation Fix Report

Date: 2026-11-16
Status: **PARTIALLY VERIFIED**

## Root cause

The organization project-creation endpoint successfully created and organization-scoped the project, but did not add the authenticated creator to the project's explicit team membership. Tenant authorization intentionally requires explicit project membership for project-domain operations. Consequently, a creator could receive HTTP 201 for project creation and then receive HTTP 403 when creating a Test Case in that new project.

This was a backend authorization-context propagation defect, not a CSRF bypass or a missing project ID in the Test Case form. The existing frontend already refreshed the active project context and prevented duplicate Test Case submissions.

## Minimal fix

In [RunnerServer.java](./src/main/java/com/example/unifiedqarunner/RunnerServer.java), the organization project creation path now:

- preserves an existing active team member and adds only the new project ID;
- creates a Project Manager membership for an active creator who has no team-member record;
- rejects inactive or missing creators safely;
- runs membership setup before returning success, and quarantines the new project if the coordinated setup fails.

No authentication, CSRF, tenant isolation, or project authorization checks were weakened.

## Regression coverage

[FinalH005CrudHttpVerificationTest.java](./src/test/java/com/example/unifiedqarunner/FinalH005CrudHttpVerificationTest.java) now covers:

- project creation through the organization API;
- project visibility after refresh/list reload;
- Manual Test Case creation;
- Automation Test Case creation;
- persistence of both cases;
- invalid Test Case input rejection;
- existing tenant, CSRF, cross-project read/write, and delete checks.

## Evidence

| Verification | Result |
|---|---:|
| Initial isolated reproduction before fix | Project HTTP 201, subsequent Test Case HTTP 403 |
| Focused post-fix HTTP regression | 3 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED |
| Organization browser coverage | 4 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED |
| User Story browser coverage | 2 PASS, 0 FAIL, 0 ERROR, 0 SKIPPED |
| Maven compile (`-DskipTests`) | PASS |
| `git diff --check` | PASS; existing line-ending warnings only |
| Live PID/port safety check | PID 23304 alive; `127.0.0.1:8081`; HTTP 200 |

Browser tests used one headless browser per isolated test-owned server and disposable storage. No live records were changed.

## Limitations

- The current live JVM was started before the latest source/classes and uses mutable `target/classes`; it does not contain this fix.
- No live browser mutation or live Test Case creation was attempted.
- A controlled restart is required to activate the fix, with the previously documented rollback/data-safety prerequisites and explicit approval.
- Selenium emitted a CDP version warning because the installed browser is newer than the Selenium DevTools artifact; the focused browser tests still passed.

## Final classification

**PARTIALLY VERIFIED** — the defect is fixed and verified in isolated HTTP/browser tests, but live activation and live verification remain pending. Production readiness remains **NOT APPROVED**.

**CONTROLLED RESTART REQUIRED — AWAITING USER APPROVAL**
