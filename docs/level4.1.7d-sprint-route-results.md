# Level 4.1.7D Sprint Route Results

## Test evidence

New test class: `src/test/java/com/example/unifiedqarunner/Level417DCompletionTest.java`.

Test `sprintBoardRouteUsesSprintBoardAuthorizationAndTenantOwnership` uses the implemented route:

`GET /api/user-stories/board?projectId={id}&sprintId={id}&offset=0&limit=10`

It asserts:

- Project-member access succeeds with HTTP 200.
- Organization B user cannot access Organization A's board: HTTP 403.
- Organization A member cannot access Organization B's project: HTTP 403.
- A member whose organization membership is changed to SUSPENDED cannot access the board: HTTP 403.

Source mapping: [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) uses `TeamAuthorization.SPRINT_BOARD` for the board route; [TeamAuthorization.java](../src/main/java/com/example/unifiedqarunner/TeamAuthorization.java) normalizes `sprints` to `sprint-board`.

Result: **PASS**, 1 test / 4 route assertions.

Existing `TenantContextResolverTest`: 6/6 PASS, confirming the underlying tenant/project and permission resolution behavior.

Unverified: every sprint mutation route and unrelated-permission negative case were not separately exercised.

