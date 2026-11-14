# D1-F-001 Compression-Ratio Decision Matrix

| Option | Security assurance | Exact ratio | Memory/disk | Compatibility | Complexity | Closure eligibility | Approval |
|---|---|---|---|---|---|---|---|
| A. Current `ZipInputStream` plus hard actual-byte limits | Strong against bounded expansion, not ratio-specific | No | Bounded by current caps | Best; already verified historically | None | Not eligible for exact-ratio criterion | None for current state |
| B. `ZipFile` metadata screening plus actual counters | Better early screening; actual caps remain primary | No; metadata only | Bounded staged file/streams | Requires ZIP variant tests | Moderate | Not eligible by itself | Narrow implementation approval if desired |
| C. Accurate accounting with an existing available library | Potentially strong if library semantics are proven | Potentially yes | Must be proven | Must test ZIP64/descriptors/malformed inputs | Moderate if truly available | Candidate | Dependency/API/security approval |
| D. New parser/library dependency | Potentially strong, dependent on selected API | Potentially yes | Must be proven | New compatibility surface | Highest among maintained options | Candidate after evidence | Explicit dependency and security approval |
| E. Risk acceptance with current hard limits | Operationally bounded; residual ratio risk accepted | No | Current bounded behavior | Best | Lowest | Only if governance accepts residual risk | Explicit security-risk acceptance |

## Decision

The safest immediate position is **A plus explicit residual-risk documentation**: retain the implemented actual-byte controls and do not introduce a speculative parser or claim exact ratio enforcement. If D1-F-001 must become closure-eligible under a mandatory exact-ratio rule, the smallest technically defensible next step is a separately approved maintained parser/library evaluation and adapter, not a generic counting stream or hand-written ZIP parser.

Recommended status: **OPEN — FEASIBILITY BLOCKER / SEPARATE APPROVAL REQUIRED**.
