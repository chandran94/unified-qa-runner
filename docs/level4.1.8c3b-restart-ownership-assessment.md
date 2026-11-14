# Level 4.1.8C-3B Restart Ownership Assessment

`AutomationExecutionManager` stores executions in the isolated `qa-runner.json` root. Valid fixtures contained authoritative `organizationId` and `projectId`. `automationExecutionVisible` checked project-to-organization assignment and active project authorization before serialization.

Organization A saw only `restart-a`; Organization B saw only `restart-b`. The same assertions passed after stopping and restarting only the isolated server against the same disposable root.

Result: **PASS for persisted execution ownership reload**.

A synthetic execution without ownership was inserted into the disposable store. The list request failed closed with HTTP 400 and disclosed neither the legacy ID nor a filesystem path. It was not auto-assigned.

Result: **PASS for fail-closed legacy handling in the tested path**.

The server uses an in-memory bounded executor and in-memory run state. No durable pending-job queue, restart recovery, or distributed worker claim protocol exists.

Result: **NOT IMPLEMENTED**, not a verification PASS.

