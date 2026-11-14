# Level 4.1.7D Test Isolation Matrix

| Class | Data root | Auth/session | HTTP | External services | Cleanup | Result |
|---|---|---|---|---|---|---|
| `TenantContextResolverTest` | Unique temporary directory per fixture | Synthetic in-memory/session objects backed by that root | None | None observed | Fixture walks and deletes only its root | Isolation PASS; 6/6 PASS |
| `OrganizationApiIntegrationTest` | Unique temporary directory per test; temporary `qa-runner.json` | Synthetic users and separate cookies/CSRF tokens | Loopback `RunnerServer` on port `0` | None observed | `server.close()` and owned-root cleanup | Isolation PASS; 3/3 PASS |
| UI refresh scenarios | No disposable browser harness executed | Not applicable | Live HTTP prohibited | Not applicable | Not applicable | BLOCKED / NOT RUN |

The source shows no use of port 8081 in either executed class. No production file path is used by the fixture constructors. The HTTP test's `RunnerServer` receives the temporary root explicitly and asks the operating system for its port.

## Counts

- Focused test classes executed: 2
- Tests executed: 9
- Passed: 9
- Failed: 0
- Errors: 0
- Skipped: 0
- Browser/UI runtime tests: 0; NOT RUN

