# Level 4.1.7D / D1-F-001 Provenance Reconciliation

## Scope and method

This is a read-only reconciliation of the current worktree, Git history, source, tests and existing feasibility reports. No Java, test, Maven, configuration, dependency or existing-report file was modified by this review. Maven, Java execution, tests, HTTP requests and application startup were not performed.

## Git provenance snapshot

| Item | Observed value | Confidence |
|---|---|---|
| Branch | `master` | High |
| HEAD | `d9c99e56e4289c31928dad5a071a552bd75555dc` | High |
| Staged changes | None observed from `git diff --cached` | High |
| Tracked modified files at review start | `AutomationMappingManager.java`, `PipelineStatusService.java`, `RemoteImportConfig.java`, `RunnerServer.java`, `TestCaseManager.java`, `index.html`, `TenantRouteIntegrationTest.java` | High |
| Relevant untracked test | `src/test/java/com/example/unifiedqarunner/ZipImportResourceLimitsTest.java` | High |
| Relevant untracked reports | Prior H-005/D1/D2 reports already present before this review | High |

Git history shows the current HEAD contains substantial multi-tenant security work, including `RunnerServer.java`, `TeamAuthorization.java` and `index.html`. `TeamAuthorization.java` is not currently modified in the worktree. `RunnerServer.java` and `index.html` are currently modified, and the ZIP-related `RemoteImportConfig.java` and `ZipImportResourceLimitsTest.java` evidence is present in the current worktree. Git cannot establish when an uncommitted change was made, by whom, or which named phase introduced it. Accordingly, “Level 4.1.7D change” is not asserted for any uncommitted line.

| File | Git classification at this review | Evidence-based attribution |
|---|---|---|
| [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) | Current uncommitted change; also present in HEAD history | Phase attribution unresolved for uncommitted lines |
| [TeamAuthorization.java](../src/main/java/com/example/unifiedqarunner/TeamAuthorization.java) | Current source is committed at HEAD; no worktree diff | Security code is committed; phase attribution cannot be inferred solely from blame |
| [index.html](../src/main/resources/static/index.html) | Current uncommitted change; relevant organization UI code also exists in HEAD | Phase attribution unresolved for uncommitted lines |
| [RemoteImportConfig.java](../src/main/java/com/example/unifiedqarunner/RemoteImportConfig.java) | Current uncommitted change | Historical phase attribution unresolved |
| `ZipImportResourceLimitsTest.java` | Current untracked file | Unattributed current-worktree evidence; not runtime-verified here |
| [pom.xml](../pom.xml) | No current worktree or staged modification observed | No dependency addition attributable to this review |

The reported “Level 4.1.7D modified `RunnerServer.java`, `TeamAuthorization.java`, `index.html`” statement is therefore only partially reconcilable: those files contain relevant implementation evidence, but the current Git state does not prove that all such changes were introduced by that phase. This is provenance uncertainty, not proof of a security regression.

## Level 4.1.7D Team Management review

| Control | Source evidence | Existing test evidence | Runtime status | Classification |
|---|---|---|---|---|
| Suspended memberships excluded from active-member UI | [index.html](../src/main/resources/static/index.html), `memberRows` filters `membershipStatus === 'ACTIVE'` | Existing tenant/authorization tests cover membership denial and revalidation, but no test was run here proving the browser rendering path | Not run | IMPLEMENTED; NOT VERIFIED at runtime |
| Historical membership/audit records preserved | `OrganizationStore.updateMembership` preserves membership identity and timestamps; membership routes call `auditOrganization`; `membershipToApiMap` returns status and timestamps | Organization API integration coverage exists, but no executed test here proves immutable historical audit retention | Not run | PARTIAL; NOT VERIFIED |
| `sprints` versus `sprint-board` authorization | [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) routes sprint GET/POST/update/start/complete operations through sprint-board authorization; [TeamAuthorization.java](../src/main/java/com/example/unifiedqarunner/TeamAuthorization.java) normalizes `sprints` to `sprint-board` | User-story and tenant authorization tests exist; not run in this phase and no dedicated HTTP regression was established here | Not run | IMPLEMENTED; NOT VERIFIED at runtime |
| Server-authoritative organization role | [RunnerServer.java](../src/main/java/com/example/unifiedqarunner/RunnerServer.java) derives organization role from tenant context and serializes `organizationRole`/`membershipStatus`; management authorization uses tenant role | Organization API tests exist; no executed role-tampering HTTP assertion in this phase | Not run | IMPLEMENTED; NOT VERIFIED at runtime |
| Stale UI refresh ordering protection | [index.html](../src/main/resources/static/index.html) aborts the prior controller, increments `tenantState.generation`, and ignores stale responses whose generation differs | No browser test was permitted or run | Not run | IMPLEMENTED; NOT VERIFIED |

The implementation classifications are source-review classifications only. They are not full runtime verification because the requested phase prohibited Maven, tests, browser execution and HTTP requests.

## Option A report chronology and gate evidence

The four requested reports were found under `docs/`:

1. [Option A preflight](./level4.1.8d-d1-f-001-option-a-preflight.md) records `PHASE A: BLOCKED / NOT VERIFIED`.
2. [Parser API verification](./level4.1.8d-d1-f-001-parser-api-verification.md) records current `ZipInputStream` use, metadata-only `ZipFile` size values, and insufficient local evidence for exact compressed-payload accounting.
3. [Dependency decision](./level4.1.8d-d1-f-001-dependency-decision.md) records that no dependency was added or approved.
4. [Option A final report](./level4.1.8d-d1-f-001-final-report.md) repeats `BLOCKED / NOT VERIFIED` and states that implementation was not entered.

These reports consistently state that the feasibility phase made no Java, test or `pom.xml` changes and ran no Maven/tests/compile. The current dirty worktree reflects changes from the broader prior task history, but it does not contradict the reports' statement about the specific feasibility phase because those reports do not claim ownership of later/current worktree changes.

## Cross-phase consistency conclusion

- The Level 4.1.7D source-implementation claims are partly supported by committed/source evidence, but provenance of uncommitted lines is unresolved.
- The Option A feasibility reports consistently identify a blocked/not-verified parser gate, no selected library, no dependency addition and no implementation in that phase.
- Current ZIP resource-limit source/test files do not establish that Option A passed or that exact compression-ratio enforcement was implemented.
- No genuine status contradiction was established. The material unresolved questions are provenance and runtime verification, not a demonstrated conflict between the reports.

## Open provenance questions

1. Which uncommitted portions of `RunnerServer.java`, `RemoteImportConfig.java` and `index.html` belong to which earlier task or phase?
2. When was the untracked `ZipImportResourceLimitsTest.java` created?
3. Were the reported Level 4.1.7D controls executed in a prior environment, or only source-reviewed?
4. Was any ZIP limit code run after the blocked Option A gate? The current worktree suggests prior ZIP-related work, but this review did not execute it and cannot assign phase ownership.

## Review conclusion

The provenance record is **partially attributable and not fully provable for uncommitted changes**. The Option A feasibility record is internally consistent and remains a blocked/not-verified gate. No existing evidence was rewritten and no tracking status was changed.

