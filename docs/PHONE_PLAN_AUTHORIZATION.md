# Bound phone authorization for plan changes

Refs #118 and #53. This is the first locally verifiable PS2 slice. It covers edits and deactivation of existing reminder plans. It uses the database adapter from #143 and the typed phone profile from #144.

The internal plan-write entry point validates the token before acquiring a database connection. The PostgreSQL adapter locks the current account, installation and session, then acquires the existing pairing guard in that order. Plan lookup, current Elder identity and active caregiver relationship checks, the existing mutation, and original receipt run through that connection. It refuses ambiguous IDs present in both identity stores. Existing anonymous plan routes continue to use their own actor path.

A new session on the original installation can recover the original receipt while the relationship is current. A different installation cannot take over the operation. Revoking the session or the relationship blocks replay as well as new effects. The receipt remains tied to the original command and plan revision. The new implementation calls the existing plan mutation algorithm, preserving frozen revisions and conflict behavior.

The PostgreSQL tests use a real store with fictional accounts, an existing plan and a consented relationship. They verify single-connection writes, edits and deactivation, replay across re-login, cross-installation rejection, session and relationship revocation, ambiguous identity rejection, and signature failure before any database connection. The existing Server and Android regression suites are also run.

Production route integration still needs the approved managed-primary composition and migrations. Pairing creation and consent, plan confirmation and reads, photo access, and event or reminder registration must adopt their relevant current-actor checks in subsequent PS2 slices. Real provider, signed APK and joint device cases remain separate.
