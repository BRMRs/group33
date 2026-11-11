# Pairing issuance HTTP preparation on main64

Local-only compatibility refresh of earlier S2 HTTP preparation. Base main64ddd4e (includes merged #44 strict code transport), plus three byte-identical #47 service/Store/original-test dependency files. All six HTTP/auth/test files remain byte-identical to the earlier S2-pairing-http-local preparation; no weaker assertions or new protocol. No formal branch, stacked PR, new commit or CI.

Production boundary remains real anonymous login, verified JWT subject lookup in current in-memory identity store, strict empty object and Idempotency-Key, shared in-memory issuance service, local typed status/header handling. No phone/SMS/password authentication, role migration, relationship authorization, durable identity, code consumption or real PostgreSQL wiring. Confirmation of HTTP range, named Server/security reviewer and dependency baseline is still required before formal team PR.

The same 17 existing real Boot/security/JWT/service tests and full current-main checks passed on this snapshot: 92 tasks, 45 executed / 47 FROM-CACHE. Older f9f29d8 results are not relabeled as this baseline's evidence; new logs/XML are retained locally. No new device or persistent HTTP restart proof.
