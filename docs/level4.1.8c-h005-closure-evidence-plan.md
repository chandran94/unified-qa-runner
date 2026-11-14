# Level 4.1.8C H-005 Closure Evidence Plan

## Scope and safety gate

This was a verification-only phase. Existing working-tree changes were preserved. No production Java, authentication/RBAC, schema, credentials, live data, cloud service, PostgreSQL instance, browser, external integration, or port 8081 was changed or started.

The safe harness used `RunnerServer` with a unique temporary root and port `0`. The server bound to loopback, and each organization used separate login cookies and CSRF tokens. Synthetic users, projects, wiki records, run objects, and report files were created only under the test root.

## Route/store inventory

- Run list/detail/report: `RunnerServer` routes `/api/runs`, `/api/runs/{id}`, and `/api/runs/{id}/report`; run state is in-memory and report files are under the test root.
- Test/mapping CRUD: `/api/testcases*` and `/api/testcase-automation-mappings`; `TestCaseManager` and `AutomationMappingManager` use `Path.of("qa-runner.json")`, which resolves from the process working directory rather than the disposable `RunnerServer` root.
- Wiki mutations: `/api/wiki/spaces`, `/api/wiki/pages`, space updates/archive, and page updates/archive; `WikiManager` uses the supplied project root.

## Execution plan

1. Run safe run/report object-ID tests using reflective insertion of synthetic in-memory run entries and synthetic report files; this avoids external execution.
2. Run safe wiki create/update/archive tests over HTTP.
3. Do not run test/mapping mutating routes because their stores cannot be proven disposable without changing production code or process configuration.
4. Run Maven compile, the two focused tests, and the established targeted security regressions sequentially.
5. Reconcile the original 14-family denominator without counting blocked coverage as PASS.

