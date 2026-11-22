# P1-F12A.1 Organization Cleanup Capability Audit

## Scope and safety

Read-only source, test, and browser inspection only. PID 23304 and loopback port 8081 were preserved. No organization, project, or membership was created or changed.

## Capability verdicts

| Capability | Verdict | Evidence |
|---|---|---|
| Organization deletion | **UNSUPPORTED** | No organization deletion method, DELETE route, or UI action was found. The organization DELETE route found is membership revocation. |
| Organization archival/deactivation | **UNSUPPORTED via management API** | `OrganizationStatus` and `OrganizationStore.updateOrganization(..., status)` exist internally, but the HTTP PATCH handler permits only name and slug; it does not expose status changes. |
| Project deletion | **UNSUPPORTED** | No project DELETE route or store deletion method was found. |
| Project archive/restore | **SUPPORTED** | POST `/api/user-stories/projects/{id}/archive` and `/restore` call `UserStoryStore.archiveProject`. |
| Story archive/restore | **SUPPORTED** | Existing `UserStoryStoreTest` verifies archive/restore without deleting data. |

## Ownership, RBAC, and tenant isolation

- Organization creation requires authenticated CSRF and an authenticated actor; the creator receives the owner membership.
- Organization listing returns only active organizations with active membership for the authenticated user.
- Organization detail and management resolve tenant context from the authenticated session and organization ID.
- Organization manager operations require OWNER or ADMIN role.
- Membership DELETE changes status to `SUSPENDED` and preserves the membership record.
- Project ownership is stored separately in `.qa-organizations.json`; immutable assignment rejects conflicting reassignment.
- Organization project listing filters by organization ownership and explicit project membership or manager role.

## Project archive/restore evidence

`UserStoryStore.listProjects(includeArchived)` filters archived projects, and `archiveProject(id, archived)` persists the flag. HTTP archive/restore routes require same-origin and authenticated-admin authorization. Existing tests cover story archive/restore, but no focused project archive HTTP test was found. Project route behavior is therefore source-supported but runtime **NOT VERIFIED** here.

## Safest disposable E2E strategy

Use a separate isolated application/storage root with synthetic identities and a dynamically assigned test port. This avoids residual records in the current running store and permits lifecycle testing.

If the current session must be used, create a uniquely named organization/project only after explicit approval, archive the project afterward, and accept that the organization remains residual because no supported delete/deactivate endpoint exists.

## Residual risk and decision

Direct file/database deletion is prohibited without separate approval. P1-F12A mutations are **NOT SAFE TO APPROVE** against the current running storage. Use isolated storage/test-server execution instead. Production readiness remains **NOT APPROVED**.
