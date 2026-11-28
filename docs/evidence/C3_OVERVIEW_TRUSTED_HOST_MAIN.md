# C3 trusted overview Host on current main

Refs #15 / #19 and PR #122. The original Host work was prepared on an auxiliary stack at `9392dc0`; this landing candidate ports only its trusted-context overview Host, accepted-Taken-time presentation, scoped tests and historical captures onto main `9ae5bbcd2baa11222f1fb2b7dc13634159b773a8` after the reusable overview component landed in #151.

## Observable boundary

The caller supplies a `CaregiverElderContext`, its session-bound `CaregiverDashboardRepository`, an explicit date range and the History callback. The Host never selects an identity, invents a date or installs a Repository. Rebinding a new session/relationship clears the former model before subscribing; a null context clears private presentation; detachment clears the ViewModel store and callbacks.

For a server-accepted `TAKEN` event with `serverAcceptedTakenAtEpochMillis`, the row shows a bilingual Elder-report timestamp separately from planned time and local sync/report evidence. The timestamp is absent for missing evidence or a non-TAKEN accepted status. The text states that ingestion is not independently verified.

The actual Debug Repository/Room instrumentation uses fictional identities and scripted HTTP. It exercises the page refresh button from scheduled to accepted Taken, then a failed refresh that retains stale accepted evidence. This is not live Server, Release navigation, a default date source or two-installation acceptance.

## Integration limits

The Debug-only empty Activity exists solely to host instrumentation and is not exported. Historical English/Chinese captures remain attributed to their original revision. #122 does not add normal-entry navigation; a production caller must still supply the trusted current-Elder context and date range. #15/#19 remain open.
