# Level 4.1.7D Stale UI Refresh Verification

## Source-only review

[index.html](../src/main/resources/static/index.html) maintains a tenant-state generation and an abort controller. `switchOrganization` aborts the previous controller, increments the generation and resets state. `refreshTenantModule` captures the generation and discards responses whose generation no longer matches; errors are likewise ignored for stale generations. The UI reads the server-returned organization role and filters the rendered member list to active memberships.

## Runtime status

No browser automation was run or added. The live application and port 8081 were not accessed. Existing Java tests do not execute browser JavaScript or prove response ordering, rapid organization switching, stale-error handling or post-revocation rendering.

| Scenario | Classification |
|---|---|
| Older response cannot overwrite newer state | SOURCE-REVIEWED |
| Rapid organization switching | SOURCE-REVIEWED; runtime NOT VERIFIED |
| Failed request cannot restore stale data | SOURCE-REVIEWED; runtime NOT VERIFIED |
| Revocation refresh does not re-display suspended members | SOURCE-REVIEWED; runtime NOT VERIFIED |
| Server-authoritative role used by UI | SOURCE-REVIEWED |
| Loading/error state consistency | NOT VERIFIED |

No defect is confirmed by this source review. A browser-level disposable harness would be required for `VERIFIED` classification.

