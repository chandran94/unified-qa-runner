# P1-F12L.0C — Isolated Startup

Status: ISOLATED STARTUP VERIFIED. The isolated child was stopped after verification; the live application was never stopped or restarted.

## Runtime evidence

- Isolated PID: `14656`.
- Isolated port: `63721` (dynamic loopback allocation; never 8081).
- Isolated URL: `http://127.0.0.1:63721/`.
- Isolated storage: `C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-JcxKmK\qa-runner-release-20261115-231044571\isolated-storage-7`.
- Child stdout/stderr: `isolated-logs-7` under the same release workspace.
- Root HTTP probe: PASS, HTTP 200.
- Startup message: `Unified QA Runner is available at http://127.0.0.1:63721/`.
- Storage root contains synthetic `qa-runner.json` and `.qa-organizations.json`; no live storage path was supplied.

The first two probe attempts used an incorrect `/api/health`/protected `/api/status` assumption. The third attempt exposed and corrected a Windows argument-quoting harness error. The final attempt used the actual public root endpoint and a corrected process launcher. These were isolated harness failures, not release-artifact failures.

## Live safety

Before and after isolated startup, port `127.0.0.1:8081` remained owned by PID `23304` and returned HTTP 200. No live process was killed, restarted, or modified. No live backup, migration, external runner, or production integration was executed.

## Classification

- IMPLEMENTED: isolated launch controls and synthetic bootstrap.
- TEST VERIFIED: startup, loopback binding, child PID, and storage-path separation.
- LIVE VERIFIED: existing PID/port health only; latest-release behavior is not claimed for PID 23304.
