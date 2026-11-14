# Level 4.1.8C H-005 Updated 14-Family Matrix

The original 14-family denominator is retained from the recovered C-3 matrix. Later pipeline-cache work remains a related post-audit boundary and is not silently added.

| # | Family | Current evidence | Status after this phase |
|---:|---|---|---|
| 1 | Database connections | C-001 HTTP and regression evidence | VERIFIED WITHIN TESTED SCOPE |
| 2 | CI/CD configuration | C-002 scoped HTTP and redaction evidence | VERIFIED WITHIN TESTED SCOPE |
| 3 | Test-case/pipeline result import | C-2B scoped import tests | VERIFIED WITHIN TESTED SCOPE |
| 4 | Automation executions | C-2A/C-3/C-3B filtering and restart evidence | VERIFIED WITHIN TESTED SCOPE |
| 5 | Local runs/execution metadata | C-3/C-3B run list/detail object tests | VERIFIED WITHIN TESTED SCOPE for exercised object operations |
| 6 | Report files | C-2C file safety plus C-3B report route test | VERIFIED WITHIN TESTED SCOPE |
| 7 | Screenshots/videos | H-003/C-2C artifact ownership tests | VERIFIED WITHIN TESTED SCOPE |
| 8 | Shared paths/catalog | C-2F/C-3 catalog tests | VERIFIED WITHIN TESTED SCOPE |
| 9 | GitHub/ZIP imports | C-2E isolated import tests | VERIFIED WITHIN TESTED SCOPE |
| 10 | Test cases/mappings/discovered assets | Representative prior tests; full CRUD/mapping HTTP test blocked by unsafe store root | PARTIALLY VERIFIED / BLOCKED |
| 11 | User stories/epics/sprints/boards | H-001/H-002/H-003 route evidence and focused attachment/wiki parent tests | VERIFIED WITHIN TESTED SCOPE; not every CRUD branch exercised |
| 12 | Wiki spaces/pages | C-3B list evidence plus this phase’s mutation HTTP tests | VERIFIED WITHIN TESTED SCOPE |
| 13 | Attachments | C-3B two-organization HTTP test | VERIFIED WITHIN TESTED SCOPE |
| 14 | Logs/plugin results/history | In-memory run output; no separate output/log API | NOT APPLICABLE for separate API; future capability not implemented |

## Coverage accounting

- Historical/current before this phase: **11/14 = 78.57%**.
- Newly verified areas: run/report object operations and wiki mutations: **+2**.
- Updated original-scope evidence: **13/14 = 92.86%**.
- Test/mapping CRUD remains the one blocked family area and is not counted as verified.
- No confirmed Critical/High vulnerability was found in executed tests.
- Report metadata object APIs, plugin output/log APIs, durable queue recovery, and remote runner/webhook features are not implemented and are not counted as HTTP PASS.

