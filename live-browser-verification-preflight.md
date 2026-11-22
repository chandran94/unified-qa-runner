# Live Browser Verification Preflight

Date: 2026-11-15
Scope: read-only verification of the existing localhost application

## Decision

**RESTART REQUIRED** — the live application is healthy and safely isolated on loopback, but the running JVM is using a mutable `target/classes` runtime that predates the latest source/class compilation. No restart was authorized or performed.

Production readiness remains **NOT APPROVED**.

## Runtime evidence

| Check | Result | Evidence |
|---|---|---|
| Process | PASS | PID 23304 is alive as `java`; start time observed as 2026-11-15 16:32:09. |
| Port ownership | PASS | PID 23304 owns `127.0.0.1:8081`. |
| Binding | PASS | Loopback-only listener; no public interface observed. |
| HTTP health | PASS | `GET /` returned HTTP 200. |
| Entry point | PASS | Command line contains `com.example.unifiedqarunner.Main`. |
| Java runtime | PASS | Microsoft JDK 25.0.5.21 executable. |
| Runtime classes | PASS | Command line references the project `target/classes` and Maven dependency classpath. |
| Storage | PASS | Runtime argument points to a disposable `%TEMP%`-scoped Phase 3E root outside the workspace. |
| Duplicate instance | PASS | No second Unified QA Runner listener was observed. |

No credentials, tokens, environment-secret values, or live records were printed or included in this report.

## Build identity and freshness

The running JVM has no verified release marker or immutable artifact identity. Its command line proves that it loaded mutable `target/classes`, not a versioned packaged release.

Read-only timestamps show:

- PID 23304 started before the latest source and class compilation.
- Main class timestamp: approximately 2026-11-15 22:06:37 +05:30.
- Newest compiled class timestamp: approximately 2026-11-15 22:06:40 +05:30.
- Newest source timestamp: approximately 2026-11-15 22:06:02 +05:30.
- Newest source identified: `src/main/java/com/example/unifiedqarunner/PlaywrightPlugin.java`.

Therefore the latest source changes are **not live**. The exact bytes loaded by the current JVM cannot be independently proven beyond the mutable classpath evidence.

## Rollback readiness

**BLOCKED.** Packaged JARs exist under `target/`, but no immutable, verified rollback artifact with validated release metadata, hash, and matching runtime configuration was established in this read-only phase. Existing rollback documents are plans, not rollback artifacts. The current `target/classes` runtime is not itself a recoverable release artifact.

Before any controlled restart, the following are required:

1. A verified consistent snapshot of the disposable runtime storage, or an explicit approved disposable-data reset plan.
2. A versioned candidate artifact with recorded hash and Java compatibility.
3. A preserved, verified prior working artifact or an accepted rollback limitation.
4. Isolated startup validation of the candidate on a dynamic loopback port.
5. Explicit user approval for one controlled restart.

## Browser verification

One browser page was used sequentially. No login credentials were available to Codex and no login or mutation was attempted.

| Workflow | Status | Evidence |
|---|---|---|
| Login page | PASS | `/` rendered the “Welcome back” form with username/email/mobile, password, Sign In, Forgot password, and Create an account controls. |
| Unauthenticated protected access | PASS | Dashboard/hash navigation redirected to `/login`; protected resource requests returned HTTP 401. |
| Dashboard | BLOCKED | Authentication required; redirect observed. |
| Settings | BLOCKED | Authentication required; redirect observed. |
| Organization submenu | BLOCKED | Authentication required; redirect observed. |
| User Story Management | BLOCKED | Authentication required; redirect observed. |
| Sprint/Kanban | BLOCKED | Authentication required; redirect observed. |
| Test Case Management | BLOCKED | Authentication required; redirect observed. |
| Defect Management | BLOCKED | Authentication required; redirect observed. |
| CI/CD | BLOCKED | Authentication required; redirect observed. |
| Reports | BLOCKED | Authentication required; redirect observed. |
| Wiki | BLOCKED | Authentication required; redirect observed. |
| QA Studio | NOT VERIFIED | Route was tested only through unauthenticated navigation; authenticated availability was not established. |

The browser also reported a CSP-blocked external Google Fonts stylesheet. This is a non-blocking visual/asset finding for this read-only pass; no source change was made. The observed 401 responses are expected unauthenticated enforcement, not authorization regressions.

## Data and safety result

- No records were created, edited, deleted, archived, or restored.
- No live storage was modified or backed up.
- No process was stopped or restarted.
- No external test, pipeline, email, commit, push, or deployment was performed.

## Final classification

**RESTART REQUIRED** for verification of the latest source changes. The current runtime itself is **LIVE HEALTHY / LOOPBACK-ONLY**, but authenticated module verification remains blocked until an authorized user-controlled browser login is available, and the latest build cannot be declared live.

Next safe action: establish an approved immutable candidate/rollback pair and obtain explicit restart approval; separately provide an authorized browser session for authenticated read-only verification.
