# Level 4.1.8D D1 — Risk Register

These are D1 audit observations only. H-005 remains CLOSED in the Security Findings Register; these observations do not reopen it without separate confirmed evidence and approval.

| Finding | Classification | Severity | Affected surface | Root cause | Required next action | Overlap |
|---|---|---|---|---|---|---|
| D1-F-001 | CONFIRMED CONTROL GAP | HIGH | POST `/api/imports/zip`; `RemoteImportConfig.extractZipFile` | 50 MB input cap exists, but no decompressed-byte, entry-count, nesting, or compression-ratio limit | D4 bounded archive design and tests | New D1 risk; related to prior C-2E import scope but not H-005 closure status |
| D1-F-002 | CONFIRMED CONTROL GAP | MEDIUM | GET `/screenshots/{file}`, GET `/videos/{file}` | Entire files are loaded with `Files.readAllBytes`; no universal response-size/streaming bound | D3 bounded streaming and resource policy | Related to artifact handling; no confirmed tenant disclosure |
| D1-F-003 | POTENTIAL | HIGH | Screenshot/video roots and generic archive/import paths | Lexical normalize plus `isRegularFile` does not establish real-path containment against symlink escapes | D3 real-path/symlink tests; D4 archive symlink policy | Related to C-2C/H-003 artifact evidence, not a demonstrated regression |
| D1-F-004 | POTENTIAL | HIGH | GitHub import and tool archive download/extraction | External/process and generic extraction paths have incomplete egress, timeout, output, and archive-resource policy | D4/D5 bounded external/import review | Separate integration/file risk |
| D1-F-005 | NOT VERIFIED | HIGH | Run logs, report content, imported assets | Redaction, content rendering and imported executable behavior are not comprehensively verified by D1 source review | D3–D5 focused evidence | Does not reopen H-005 by itself |

No D1 finding was exploited, no HTTP request was sent, and no remediation was applied. The confirmed control gaps are source-backed design deficiencies requiring approval for D2–D5.

