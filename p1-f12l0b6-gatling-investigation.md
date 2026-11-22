# P1-F12L.0B.6 — Gatling investigation

## Result

**IMPLEMENTED / TEST VERIFIED for host-controlled staging. LIVE VERIFIED: NO.** No real load test or external Gatling process was run.

`GatlingPlugin.execute` invokes Maven `test` with `-Dgatling.simulationClass` and optional `-Dbase.url`, from the directory containing the discovered simulation POM. The repository POM has no Gatling dependency or Gatling Maven plugin, the local Maven cache has no Gatling integration, and no local Gatling documentation or simulation project is present. The prior implementation discovered `target/gatling-simulations` and copied it directly into the durable report tree.

No Gatling-specific output flag was invented. The verified host-controlled approach uses Maven’s standard `-Dproject.build.directory=<project>/target/runner-staging/gatling-<id>/maven-target`. The process has a 30-minute bound. On completion, the existing `target/gatling-simulations` discovery is checked under that isolated build directory and is published through `ArtifactPublicationService`; staging is discarded afterward. The exit code remains authoritative, so a failed process cannot become PASS.

## Evidence and limitation

- Gatling version: not installed or declared in this repository; therefore version-specific output-folder support is unverified.
- Working directory: simulation POM parent, unchanged.
- Report path: isolated Maven build directory, expected `gatling-simulations`; legacy durable path is no longer written by this host invocation.
- Recovery: a published report is durable and included in backup; active staging is excluded.
- Test: `GatlingPluginSafetyTest` verifies the command contains only the standard Maven build-directory relocation and no unverified Gatling results flag.

Because no real third-party Gatling POM was available, actual plugin compatibility and report generation remain **BLOCKED / NOT LIVE VERIFIED**. Production readiness remains **NOT APPROVED**.
