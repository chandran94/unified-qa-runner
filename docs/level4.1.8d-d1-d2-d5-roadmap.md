# Level 4.1.8D D1 — D2–D5 Roadmap

All phases below require separate approval. **No phase was started by D1.**

## D2 — File Upload and Attachment Security

- Scope: attachment upload/list/read/delete, parent ownership, file type/size/content, headers and CSRF/RBAC.
- Priority: High.
- Dependencies: D1 inventory; disposable storage; no production data.
- Minimal implementation: only approved controls for confirmed gaps; no schema migration.
- Tests: two organizations, revoked membership, forged parent, traversal, MIME spoofing, redaction, persistence.
- Windows constraints: one bounded Maven process, tiny files, loopback only, no external provider.
- Stop conditions: unproven store isolation, need for auth/schema redesign, or live-data access.
- Exit: all implemented attachment operations have traceable evidence and no confirmed High/Critical issue.

## D3 — Artifact and Filesystem Security

- Scope: screenshots, videos, reports, logs, downloads, streams, symlinks, real-path containment, deletion and cleanup.
- Priority: High.
- Dependencies: D1-F-002/D1-F-003 and H-003/H-005 evidence preservation.
- Minimal implementation: bounded streaming and real-path checks only where approved.
- Tests: cross-tenant object access, symlink escape, traversal, large-file behavior, bulk deletion, revoked access.
- Windows constraints: disposable roots, no port 8081, no large artifacts, bounded streams.
- Stop: physical storage ownership cannot be established without migration.
- Exit: every implemented file route is independently authorized and contained.

## D4 — Import and Export Security

- Scope: ZIP/GitHub imports, extraction, egress, exports, decompression limits, executable content and temporary files.
- Priority: High; D1-F-001 is the first candidate.
- Dependencies: no live external services; isolated provider mocks only.
- Minimal implementation: bounded archive/resource policy and safe export ownership; no new integration platform.
- Tests: ZIP Slip, symlink entries, entry/size limits, unsafe URLs, project ownership and cleanup.
- Windows constraints: no ZIP bombs, no external clone, bounded payloads and timeouts.
- Stop: safe archive testing requires production data or infrastructure changes.
- Exit: archive/import/export boundaries fail closed and resource limits are evidenced.

## D5 — Consolidated Verification

- Scope: two-organization matrix across D2–D4; RBAC/CSRF, legacy/unassigned resources, audit evidence and regressions.
- Priority: release gate.
- Dependencies: D2, D3 and D4 completion.
- Tests: negative HTTP cases, parent-child mismatch, revocation, list filtering, no metadata/path/secret leakage.
- Windows constraints: sequential focused groups; no full suite or live services.
- Stop: any confirmed Critical/High vulnerability or incomplete isolation.
- Exit: evidence reconciliation complete, residual risks accepted by governance, and production readiness separately approved.

