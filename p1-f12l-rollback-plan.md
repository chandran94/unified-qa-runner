# P1-F12L Rollback Plan

## Status

**NOT EXECUTED.** This document is a plan only. PID 23304 and port 8081 were not stopped or restarted.

## Current runtime

- Process: PID 23304
- Listener: `127.0.0.1:8081`
- Health: HTTP 200
- Runtime: Microsoft JDK 25.0.5.21
- Launch shape: bounded Java process, Maven-resolved classpath, `com.example.unifiedqarunner.Main`, disposable Phase 3E storage, `--port=8081`
- Current build identity: **UNKNOWN** beyond the observed `target/classes` classpath; no verified artifact hash was established.

## Candidate

- Source workspace: current `unified-qa-runner` checkout
- Candidate compilation: `mvn -q -DskipTests compile`, exit 0
- Candidate focused tests: 21 PASS, 1 SKIPPED, 0 FAIL, 0 ERROR
- Candidate artifact: **NOT READY FOR ACTIVATION** until a versioned artifact/hash and startup evidence are recorded. The existing target JARs are not accepted as a rollback pair.

## Preconditions before approval

1. Reconfirm PID 23304 ownership and loopback listener immediately before any action.
2. Create a unique, non-overwriting backup of the disposable storage and validate it.
3. Record candidate artifact hash and exact classpath/startup command.
4. Confirm no migration or destructive startup behavior.
5. Confirm the intended browser account/session and read-only verification scope.
6. Obtain separate explicit restart approval.

## Controlled restart procedure

Only after approval: gracefully stop the verified task-owned PID 23304, confirm port 8081 is free, launch the recorded candidate with the same disposable storage and loopback binding, and perform bounded HTTP health checks. Do not retry automatically.

## Rollback procedure

If candidate startup or health fails, stop only the newly started task-owned process after confirming its PID, restore the previously recorded runtime artifact and exact launch configuration, and start only after a new explicit approval if another startup attempt is required. Never restore a backup over newer data automatically.

## Current rollback readiness

**BLOCKED.** The previous running artifact and validated backup are not sufficiently identified for a safe rollback claim.
