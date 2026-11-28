# Current composed calendar evidence

Refs #15, #19; PR #124. Captured on 2026-11-28 (UTC+08:00).

## Version and capture scope

Production-source baseline: `f50784dc3ff198082a831d1942018cca3dc09b79`.
Samsung SM-F9260 / Android API35, unfolded display; actual system font scale **1.15**, not200%.
The current source was built offline with an isolated Debug application ID
`com.group33.medicationreminder.c3manual124` and an instrumentation-supplied fictional
Repository. The capture driver is not shipped product code or part of this delivery.
The app/test APK build passed (75 tasks:26 executed,49 up-to-date).

## What these two frames show

English and Simplified Chinese views of the same no-default-date calendar flow:
November2026 is a display month; both range endpoints are unselected and confirmation
is disabled. All seven weekday columns, dates and confirmation/cancel controls are
visible together in this device configuration, so duplicate viewport frames were not
added. Opening and cancelling the calendar left the fixture history-read count at0
in each language. The isolated application's previous language setting was restored.

The author confirmed both pictures' content and privacy. Configured vision.js visual
assistance returned successfully for each image and did not identify text clipping,
system-bar obstruction or real personal data. This is auxiliary review, not human
sign-off on untested behavior.

## Files and SHA-256

| Language | File | SHA-256 |
|---|---|---|
| en | [en-empty-calendar.png](en-empty-calendar.png) | `72319ade9eae80eac476b69070e95a5042d7e4e47ae243ca21b03e7253258c5c` |
| zh-CN | [zh-empty-calendar.png](zh-empty-calendar.png) | `8bb51a2a11e1bab2d494ece27244b38fb722535771158045f05f5c76a9cd0c1b` |

## Evidence limits

These frames do not establish200% font/small-screen behavior, TalkBack, grayscale,
formal navigation, trusted production dates, live HTTP/Server, cross-device behavior
or complete happy-path acceptance. Existing tests/manual results retain their own
versions and configurations. November2026 must not be interpreted as an authoritative
selected/read date. No production source or tracked test was modified for these frames.
The earlier explicit-date screenshots remain historical evidence; these two frames
supplement them rather than relabel them as current.

This package contains only images and evidence documentation/metadata. No full JVM,
device or Server test rerun is claimed for its addition. Final evidence-commit CI and
author/affected-role checks remain separate; PR #124 stays Draft.
