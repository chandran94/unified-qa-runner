# P1-F12L.0 Safe Rollback Procedure

## Approval boundary

This is a preparation document only. It was not executed. Stopping PID 23304, taking a quiesced snapshot, changing the runtime, restoring files, or restarting port 8081 requires separate explicit approval and a maintenance window.

## Required preconditions

1. Reconfirm PID 23304, executable, command line, storage root, and `127.0.0.1:8081` ownership.
2. Confirm the storage root is still disposable and no unrelated process uses it.
3. Gracefully quiesce writes through a supported application procedure; do not copy active files as a claimed consistent backup.
4. Create a non-overwriting snapshot of the complete storage root: `qa-runner.json`, `.qa-organizations.json*`, `.qa-attachments`, `target` media/reports, `imports`, and relevant metadata.
5. Validate the snapshot with a manifest and hashes without exposing file contents.
6. Preserve the currently loaded runtime artifact or record a reproducible copy and hash before replacement.
7. Compile and test the candidate separately; record its classpath, artifact/hash, Java version, storage root, and startup arguments.
8. Obtain explicit restart approval.

## Controlled activation

After approval only:

1. Verify PID 23304 still owns port 8081 immediately before shutdown.
2. Gracefully stop only that confirmed task-owned process.
3. Confirm port 8081 is free.
4. Start the candidate with the same approved storage root, Java 25 runtime, localhost binding, and recorded arguments.
5. Confirm one listener, HTTP 200, storage path, and sanitized startup logs.
6. Perform authenticated read-only browser smoke checks.

## Rollback triggers

Rollback is required if startup fails, storage validation fails, schema incompatibility appears, HTTP health is not 200, listener is not loopback-only, or authenticated read-only smoke checks show data loss or authorization regressions.

## Restoration

1. Stop only the newly started task-owned candidate after reconfirming its PID.
2. Restore the preserved prior runtime artifact and exact launch configuration.
3. Restore the validated storage snapshot only if the approved recovery decision confirms it is older than and appropriate for the current data; never overwrite newer data automatically.
4. Start only with explicit approval and re-run bounded health checks.
5. Record the outcome and retain the failed candidate logs for review after secret sanitization.

## Current feasibility

**BLOCKED.** No verified consistent snapshot or recoverable current runtime artifact is available from this read-only phase.
