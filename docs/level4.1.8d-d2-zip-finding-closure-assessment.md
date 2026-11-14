# Level 4.1.8D D2 D1-F-001 Closure Assessment

## Finding

D1-F-001 — HIGH: user ZIP import lacked adequate resource limits.

## Assessment

The user-controlled `/api/imports/zip` extraction path now has bounded archive input, entry count, actual per-entry decompressed bytes, aggregate decompressed bytes, path depth, duplicate path handling, non-recursive nested archive behavior, and partial-extraction cleanup. Existing ZIP-Slip normalization, trusted-root containment, project authorization, and import ownership assignment remain in place.

Focused synthetic tests and the existing remote-import tenant isolation regression passed 7/7, and compile passed.

## Closure decision

**NOT ELIGIBLE — VERIFICATION REQUIRED / SEPARATE APPROVAL FOR RATIO CONTROL.**

Exact compression-ratio enforcement is not implemented or claimed. The current JDK `ZipInputStream` integration does not provide reliable compressed-byte accounting for a defensible exact ratio policy. Stronger ratio enforcement may require a parser/dependency or broader archive-processing change, which is outside this approval. D1-F-001 remains tracked as open pending acceptance or a separately approved solution.

H-005 remains CLOSED. D1-F-002 remains OPEN/UNREMEDIATED. D3-D5 remain NOT STARTED. Production readiness remains NOT APPROVED.
