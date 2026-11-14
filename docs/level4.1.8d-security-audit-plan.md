# Level 4.1.8D Security Audit Plan

**STATUS: PLAN ONLY — NOT STARTED.**

## Objectives

Assess file-backed tenant isolation, filesystem containment, and sensitive artifact handling without changing production controls.

## Scope and exclusions

In scope: uploads, attachments, artifacts, report/download boundaries, import/export, ZIP extraction, temporary files, path and symlink handling, ownership, RBAC/CSRF, sensitive-data leakage, and audit logging. Excluded: fixes, schema migration, PostgreSQL, cloud deployment, plugins, durable queues, remote runners, and live integrations.

## Threat model

Two organizations, separate projects and roles; attacks include cross-tenant object IDs, forged parent IDs, traversal, symlink escape, unauthorized deletion/overwrite, stale membership, secret disclosure, and unsafe temporary-file reuse.

## Control areas

- File upload security: size, type, name, content and destination validation.
- Attachment access authorization: parent ownership, membership, RBAC, read/write/delete checks.
- Artifact download security: object authorization before metadata or streaming.
- Import/export authorization: project ownership and export filtering.
- ZIP/path traversal: canonical extraction roots, entry limits, traversal rejection.
- Symlink/filesystem containment: real-path checks, regular-file checks, approved roots.
- File type and size validation: bounded memory and streaming behavior.
- Tenant/project ownership: server-side authoritative mappings and fail-closed legacy handling.
- RBAC and CSRF: mutation protections and same-origin requirements.
- Sensitive data: redaction of credentials, tokens, paths and error details.
- Temporary-file lifecycle: unique names, cleanup ownership, bounded retention.
- Audit logging: actor, tenant, project, resource and outcome without secrets.

## Isolated verification strategy

Use a disposable storage root, loopback-only ephemeral server, synthetic Organization A/B fixtures, separate sessions, bounded timeouts, no external providers, and persisted-state assertions. Prove every reachable store is isolated before mutation tests.

## Execution constraints

Windows 8 GB RAM: one Maven process, sequential focused tests, no `mvn clean`, no full suite, no Docker/PostgreSQL, no browser automation unless essential, no port 8081 interruption, bounded workers/retries/HTTP waits, and cleanup only of task-owned resources.

## Evidence and report requirements

Record exact routes, source methods, fixtures, requests/responses, expected and actual outcomes, test commands/counts, blocked/not-implemented areas, Git status, and `git diff --check`. Do not treat source review or HTTP 200 alone as proof.

## Risk classification

Critical: cross-tenant credential disclosure or arbitrary file access. High: cross-tenant artifact read/mutation, traversal/symlink escape, or unauthorized import/export. Medium: incomplete redaction, retention, or audit evidence. Low: non-sensitive operational disclosure.

## Exit criteria

Every implemented in-scope boundary has traceable two-organization evidence, no confirmed unresolved Critical/High issue remains within tested scope, legacy records fail closed, relevant regressions pass, isolation is proven, and production readiness remains a separate gate.

