# C3 overview component current-main landing candidate

Refs #15 / #19. Original #95 was merged only into the auxiliary `feature/19-c3-overview-base` branch at `a834423adcad80eb163c67f9ae786bb36d359753`; it has not landed in main. This candidate replays that original component with `cherry-pick -x`, retaining its original author and source attribution, onto current #41 `714efc9a952be34c4d7a78935878213a83e5d18c` and main `9e5621393c3d130bc65ec6754d0531c363c9cf9d`.

The existing ViewModel implementation is already in main via #116. #41 retains its original missing tests; this candidate depends on that exact test source and adds the original #95 reauthentication-presentation control. No current ViewModel, Shared, Server, Room schema, HTTP, date authority or production navigation is rewritten.

All original #95 component, resource, tests and two captured-image bytes are preserved from a834423. The Debug manifest combines the current main/#41 declarations, including the reminder receiver, with the original permission-protected OverviewTestActivity. Its exported preview retains android.permission.DUMP and remains Debug-only. Current contributor histories and complete checkout hashes are reconciled; the old entire feature-baseline manifest is not copied.

This is a component source landing candidate, not live Host/normal-entry integration. #120/#122/#124 must consume actual current prerequisites and retain their declared remaining scope. The historical component/TalkBack/capture evidence belongs to the original source/author. Current build/device evidence, cache modes, exact current CI and independent review are recorded separately in the PR after execution. No original screenshot is labelled a new capture, and no actual Server/cloud/phone or complete happy path is claimed.

Normal non-author C1/E review of the complete current component is still required; a historical auxiliary-branch approval does not approve this head. BRMRs contributes current-main integration and cannot self-approve. #15/#19 stay open.
