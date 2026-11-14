# Level 4.1.8D D1 — Threat Model

Classification is evidence-based and read-only. “Confirmed” means the source demonstrates a missing control; it does not mean exploitation was executed.

| ID | Threat | Status/severity | Existing control | Gap/evidence | Verification |
|---|---|---|---|---|---|
| T01 | Cross-tenant attachment disclosure | MITIGATED / HIGH | ATTACHMENT ownership before serve/delete | Parent/list matrix still needs D2 verification | Two-org attachment matrix |
| T02 | Cross-project artifact disclosure | MITIGATED / HIGH | Artifact ownership filters and run ownership | Physical root remains shared | D3 two-org tests |
| T03 | Direct object references | MITIGATED / HIGH | Project ownership checks on reviewed routes | Coverage varies by nested route | D2/D3 route matrix |
| T04 | Path traversal | MITIGATED / HIGH | Normalize/name checks and report real-path helper | All roots not uniformly real-path checked | D3 traversal tests |
| T05 | ZIP Slip | MITIGATED within checked path / HIGH | `RemoteImportConfig.extractZipFile` normalizes and checks target root | Symlink/edge-entry behavior unverified | D4 archive tests |
| T06 | Symlink escape | POTENTIAL / HIGH | `Files.isRegularFile` and lexical containment | Screenshot/video/report differences; screenshot/video do not use real-path containment | D3 symlink tests |
| T07 | Decompression bomb | CONFIRMED CONTROL GAP / HIGH | 50 MB request/ZIP input cap | No uncompressed-byte, entry-count or nesting cap in ZIP import loop | D4 bounded archive tests |
| T08 | Oversized uploads | MITIGATED / MEDIUM | 10 MB attachment and 50 MB ZIP request caps | Multipart overhead and decompressed output need limits | D2/D4 tests |
| T09 | Malicious executable uploads | MITIGATED for attachments / MEDIUM | Attachment extension allowlist | Imports can contain arbitrary repository files | D4 execution boundary review |
| T10 | MIME spoofing | MITIGATED / MEDIUM | Signature checks for image/PDF and NUL check for text | Limited file types only | D2 content tests |
| T11 | Unsafe filenames | MITIGATED / MEDIUM | Basename extraction, control removal, generated storage name | ZIP/import names need broader policy | D2/D4 tests |
| T12 | Content-Disposition injection | MITIGATED / MEDIUM | `safeHeaderFilename` | Header behavior not HTTP-tested in D1 | D3 response tests |
| T13 | Unsafe inline rendering | POTENTIAL / MEDIUM | CSP on report; attachment allowlist | Inline attachment content policy needs verification | D2/D3 tests |
| T14 | Stored XSS in uploaded content | NOT VERIFIED / HIGH | CSP/report header; content allowlist | Text/CSV/log served behavior not fully traced | D2 content rendering tests |
| T15 | Sensitive data in reports/logs | NOT VERIFIED / HIGH | Run ownership; no-store report header | Log/report redaction matrix incomplete | D3/D5 tests |
| T16 | Credential/token leakage | MITIGATED within prior evidence / HIGH | Prior C-002 and report evidence | D1 did not re-run tests | Preserve prior evidence; D5 regression |
| T17 | Temporary-file persistence | POTENTIAL / MEDIUM | ZIP temp deleted in finally; attachment temp deleted in finally | Failure/crash cleanup unverified | D2/D4 lifecycle tests |
| T18 | Unsafe recursive deletion | POTENTIAL / HIGH | Per-file visibility filters for screenshot/video bulk deletion | Root/symlink and race behavior unverified | D3 deletion tests |
| T19 | TOCTOU race | NOT VERIFIED / MEDIUM | Normalize then access | No atomic real-path/open verification | D3 race tests |
| T20 | Missing file ownership metadata | POTENTIAL / HIGH | Additive `ProjectOwnershipManager` records | Physical files are shared and legacy metadata may be absent | D3 inventory/reload tests |
| T21 | Legacy/unassigned file access | MITIGATED within tested scope / HIGH | Unresolved ownership is denied/quarantined | Complete file-family matrix absent | D5 legacy tests |
| T22 | CSRF bypass on mutations | MITIGATED within tested scope / HIGH | Same-origin/CSRF route gates | D1 did not execute requests | D5 regression |
| T23 | RBAC bypass on downloads | MITIGATED within tested scope / HIGH | Permission and ownership helpers | Nested attachment/list breadth incomplete | D2/D3 HTTP tests |
| T24 | Unauthorized bulk export | NOT VERIFIED / MEDIUM | CI/CD permission gates | General export inventory is limited | D4 export tests |
| T25 | Import SSRF/redirects | POTENTIAL / HIGH | GitHub URL prefix check | Network egress/redirect policy not comprehensive | D4 isolated URL tests |
| T26 | Unsafe archive extraction | CONFIRMED CONTROL GAP / HIGH | ZIP Slip lexical check | Resource limits and generic extractor edge cases absent | D4 archive tests |
| T27 | Executable imported content | POTENTIAL / HIGH | Imports are cataloged before selection | Execution policy for imported files is not a complete file control | D4 execution tests |
| T28 | Cross-tenant enumeration | MITIGATED within tested scope / HIGH | Server-side list filters | Full file metadata matrix not executed | D5 two-org tests |
| T29 | Insufficient audit logging | NOT VERIFIED / MEDIUM | Some activity/audit records | File access/deletion audit coverage incomplete | D5 audit review |
| T30 | Disk exhaustion/resource abuse | CONFIRMED CONTROL GAP / HIGH | Request caps and bounded queues | ZIP expansion and whole-file reads lack universal limits | D2–D4 resource tests |
| T31 | Unauthorized artifact deletion | MITIGATED within tested scope / HIGH | Ownership filters on reviewed delete paths | Symlink/race behavior unverified | D3 tests |
| T32 | Backup/export exposure | NOT IMPLEMENTED / MEDIUM | No backup download API found | Backup/restore boundary not present in current route set | Future architecture review |
| T33 | Report generation injection | POTENTIAL / MEDIUM | Report root containment/CSP | Generated content safety not fully assessed | D3 report tests |
| T34 | Stale revocation | NOT VERIFIED / HIGH | Request-time membership helpers | Background/artifact publication revalidation needs D5 evidence | D5 revocation tests |
| T35 | Filesystem permission misconfiguration | NOT VERIFIED / MEDIUM | Java path checks | OS ACLs were not inspected or changed | Deployment/operations review |

