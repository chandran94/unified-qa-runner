# H-005 Mapping Contract Correction Proposal

No production code was changed.

The smallest proposed production correction is to make mapping-list authorization use each mapping's authoritative `testCaseId`/project ownership, rather than treating every mapping as an `AUTOMATION_EXECUTION` resource. The existing tenant, project and RBAC helpers should be reused. Query filtering should occur after authorization, with existing 403/404 and list-filtering behavior preserved.

Affected code:

- `src/main/java/com/example/unifiedqarunner/RunnerServer.java:523-533`, mapping GET handler.
- `src/main/java/com/example/unifiedqarunner/RunnerServer.java:3166`, generic required-field error source.
- `src/main/java/com/example/unifiedqarunner/AutomationMappingManager.java:39-65`, mapping creation schema.

Required focused approval tests: authorized OrgA list returns 200; OrgA cannot list OrgB mappings; cross-project and revoked membership access fail closed; unassigned mappings are hidden; POST/DELETE retain authentication, CSRF, RBAC and ownership checks; unauthorized mutations do not persist.

This requires explicit approval because it changes production authorization behavior. It is outside the current storage-injection authorization.
