# C1 acceptance audit — 2026-11-27

Sources: Issues [#10](https://github.com/BRMRs/group33/issues/10), [#19](https://github.com/BRMRs/group33/issues/19), [#118](https://github.com/BRMRs/group33/issues/118), the engineering playbook and C1 role card.
Baseline: main `08f901ac4e44393dc850459caf3924e463f3f5c9`. Agent-assisted assessment, not a human signature, accepted contract or Issue closure.

## #10 identity, pairing and selected Elder

| Acceptance | Actual source/evidence | Remaining |
|---|---|---|
| Shared phone entry and role routing | Draft #88 `3cb579a7`: LoginFlowViewModel, FusionPhoneAuthRepository, CommonPhoneSession; exact-head four CI jobs pass | Actual supplier one-tap/SDK SMS, fixed-role account recovery and authorized business entry require #118 reviewed service/configuration/package |
| Current session, expiry/logout and old-response isolation | Main PrivateSessionStore; #88 common-store/loopback/generation regressions, S1 #153 correction retained | Device/fictional transport evidence is not deployed Server or provider acceptance; current S1/S2 review remains |
| Password/reset | #88 has explicit unavailable behavior; #118 treats these as later optional extensions | Do not invent endpoints or mark the original broad #10 checkbox complete; retain recorded #49/#118 decisions |
| Pairing request/pending/cancel/recovery | Merged #112; PairingFormViewModel/View, DebugChainRepository, receipt regressions; versioned HTTP evidence in C1_PAIRING_MAIN_INTEGRATION.md | Retain evidence versions; phone-authenticated and two-normal-UI acceptance remain separate |
| Active-Elder selection and scope isolation | CaregiverHomeActivity.renderElderSelector, CaregiverContextResolver, ChainViewModel; PairingMultiElderOnlineTest/PairingSessionIsolationOnlineTest | Anonymous Debug evidence is not a production phone chain; final C2/C3 normal-entry composition remains |

## #19 shared language, navigation and accessibility

| Acceptance | Actual source/evidence | Status / next action |
|---|---|---|
| Shared tokens and sizing | colors.xml/styles.xml/dimens.xml; caregiver body18sp/support16sp; labelled bottom navigation; current-Elder min56dp | Source evidence alone does not prove contrast in every state, grayscale or spoken order |
| Follow system / saved manual choice | LocalLanguageSettingsRepository, Group33Application.applyLanguage; LocalLanguageSettingsRepositoryTest and fallback JVM tests | Stored choice/reopen covered; process lifecycle and retained business data need their own evidence |
| Current-language button, switching and recreation | #127 `6e70e303`: API27 navigation/login6/6, Debug277, current CI all pass | Scoped pass; signed-out navigation fixture does not prove active-account/paired-business retention |
| Language change retains identity/relationships/plans/events | Separate language and pairing/session tests; versioned populated pairing evidence | Current-version combined populated-state language-switch acceptance not demonstrated. Add a bounded persistence regression with explicitly test-only transport or controlled service; distinguish from supplier/production acceptance |
| Current Elder field reading label | Main had a visible title, hint disabled and no labelFor association; this candidate associates title/selector | API27 before:2 localized label failures/1 persistence control pass. After:3/3 pass with byte-identical test APK; see C1_ELDER_SELECTOR_LABEL.md. Non-author/E review remains; no wire/schema change |
| Keyboard/evidence rows | #140 `edd1435f`: API27 keyboard/row15/15, Debug277, current CI all pass; opaque Debug host, own-window drawing | Scoped pass. First global-window captures were rejected, not committed; own-window drawing is not compositor/TalkBack acceptance |
| System200%,320/360dp,long input | Older versioned API35 keyboard/preview evidence; latest API27 retains system font1.0; row fixture configures2x | Current-head API35/system-font/small-width acceptance still needs execution; configured2x is not system200% |
| TalkBack/grayscale/contrast | Labels and text/contrast assertions support inspection; old manual records retain original attribution | Current-head complete spoken order, grayscale and screen/state contrast not demonstrated. Metadata checks cannot create human sign-off |
| Shared Elder photo/actions | #19 also contains E-owned V2 screens and component agreement | Keep with E/#24/#21 and accepted component/token decisions; not all of #19 belongs to C1 |

## #132 production-date consumer

Draft #132 `5a4d76ae` has11 passing date tests and current-head CI. Original resolver and frozen patch remain unchanged. The accepted #130 boundary, S1/S2 production date source and final normal-entry composition remain absent. C1 needs full current authorized context plus an Elder execution date stream, including midnight/zone/session/relationship invalidation. No caregiver device/UTC/plan-date fallback is authorized.

## Handoff and completion

Task status/dependencies/blockers belong in existing Issues; implementation review belongs in PRs. Group messages are not required and cannot establish decisions. Local tests, CI and agent assessments do not complete broad Issue checkboxes. Retain current-version non-author/role review, resolved discussion and lead/delegate merge. Review target is one working day; after two working days without response the lead records a named backup in the relevant Issue. No automatic backup or self-approval.

The field-label repair is implemented and device-verified in this candidate. Independent next work: cover populated language-switch persistence and execute current screen accessibility checks. Record scope, actual commands/results and remaining dependencies in #19 and linked PRs.
