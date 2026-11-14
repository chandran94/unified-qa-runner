# Level 4.1.8C-3B HTTP Security Results

## Test class

[RemainingH005HttpSecurityVerificationTest](../src/test/java/com/example/unifiedqarunner/RemainingH005HttpSecurityVerificationTest.java) is test-only.

| Test | Result | Evidence |
|---|---:|---|
| `attachmentsEnforceParentProjectOwnershipOverHttp` | PASS | Owner upload `201`, owner read `200`, foreign read/delete/forged-parent upload `403`, missing CSRF `403`, delete succeeds, later read fails closed `400`/`404` |
| `persistedOwnershipSurvivesIsolatedServerRestartAndUnassignedRecordsStayHidden` | PASS | A sees only A and B sees only B before and after isolated server restart |
| `unassignedLegacyExecutionFailsClosedOverHttp` | PASS | Missing ownership caused `400`; no legacy ID or filesystem path disclosed |

Focused result: **3/3 passed**.

## Routes verified

- `POST /api/user-stories/stories/{storyId}/attachments`
- `GET /api/user-stories/attachments/{id}`
- `DELETE /api/user-stories/attachments/{id}`
- `GET /api/automation-executions`

The attachment test used tiny synthetic content only.

## Not implemented or not applicable

- No separate report metadata object-ID API was found. `/api/runs/{id}/report` file-serving controls were covered by C-2C.
- No separate plugin output/log persistence or HTTP download endpoint was found. `RunEntry.output` is in-memory.
- The queue is in-memory. Durable pending-job recovery is not implemented and is not claimed as PASS.

