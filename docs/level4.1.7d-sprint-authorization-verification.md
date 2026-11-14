# Level 4.1.7D Sprint Board Authorization Verification

## Source review

- [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) routes sprint list, create, update, start, complete and story-association operations through project access checks.
- [TeamAuthorization.java](../src/main/java/com/example/unifiedqarunner/TeamAuthorization.java) defines `SPRINT_BOARD` and normalizes the legacy `sprints` domain to `sprint-board`.
- The project/tenant resolver tests establish authoritative organization/project ownership and deny cross-tenant or cross-project resolution.

## Executed evidence

`mvn -Dtest=TenantContextResolverTest test`: 6/6 PASS.

The executed class verifies project ownership, active membership, permission resolution and cross-tenant denial primitives, but does not send Sprint Board HTTP requests. `OrganizationApiIntegrationTest` verifies organization boundary/CSRF behavior, not the sprint route itself.

| Scenario | Classification |
|---|---|
| Built-in role recognizes `sprint-board` | SOURCE-REVIEWED |
| Authorized project user accesses Sprint Board | SOURCE-REVIEWED; HTTP scenario NOT VERIFIED |
| Unauthorized/cross-tenant user denied | PARTIALLY VERIFIED through resolver primitives; sprint HTTP route NOT VERIFIED |
| Suspended member denied | VERIFIED at resolver primitive scope; sprint HTTP route NOT VERIFIED |
| No unrelated permission escalation | SOURCE-REVIEWED; runtime NOT VERIFIED |
| Legacy `sprints` compatibility | SOURCE-REVIEWED via normalization; runtime NOT VERIFIED |

