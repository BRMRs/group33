# Phone plan write runtime and primary principal registry

Refs #118 / #53. S1/lead is the recorded temporary implementation backup for this C slice; S2 remains accountable and the required Server/security reviewer, with C1 client review. This child inherits #155 runtime and #145's existing plan transaction algorithm. Source inheritance is not main adoption or approval.

## Actual boundary

```text
POST  /api/v1/elders/{elderId}/reminder-plan-confirmations
GET   /api/v1/elders/{elderId}/medications
GET   /api/v1/elders/{elderId}/reminder-plans
PATCH /api/v1/reminder-plans/{planId}
POST  /api/v1/reminder-plans/{planId}/deactivate
  strict typed-phone bearer filter (before body binding / database work)
  accepted plan wire parser + original operation key (for writes)
  plan confirmation / mutation (writes):
    same primary: current account → installation → session → pairing guard
      current principal/relationship → original receipt or effect+receipt → commit
  plan / medication collection reads (GET):
    same primary: single read-only REPEATABLE READ snapshot
      current account/installation/session + principal directory + self-Elder or active Caregiver relation → snapshot items + retained revisions → rollback
```

Only these five protected ReminderPlan paths are opened in phone mode when `app.plan.enabled=true`. Their anonymous controller is mutually exclusive. Every other unfinished PS2 business path stays denied by the fallback chain. The disabled default keeps the legacy preview contract. A phone token is never passed to the anonymous JWT decoder or anonymous actor resolver. The HTTP filter stores redacted claims and no bearer credentials in the transient security context; the context is cleared after the request. The existing services independently validate the typed token before their database connection and recheck current identity/authority inside the actual write/receipt or read-only snapshot transaction.

The composed operations use #155's exact primary store, JWT profile and UTC clock, not a second authentication database or a parallel feature session. Accepted plan payloads, coercions, idempotency header parsing, status/error envelopes, no-store, request IDs, revision conflicts and original receipt DTOs share one `PlanHttpSupport` with the existing anonymous controller. No new wire fields or arbitrary client-selected actor IDs are introduced.

Same-installation re-login may replay the original command while current session/relationship authorization holds. Another installation gets409 and cannot take over that receipt. Current session revocation gets401; relation revocation/inaccessible resource gets404; a phone Elder editing its own plan gets403 without changing role. Malformed requests have no effects. Normal C1/S2 independent review remains mandatory.

## Why V13 is required for full phone business adoption

Actual V10 still binds `dose_group_operation.elder_id` only to `anonymous_identity(id)`. A legitimate phone Elder without an anonymous alias therefore cannot persist a dose receipt. V13 is a **new forward candidate**, after #102's reserved V11 and #155's V12; S2 must coordinate the number and final release before adoption. Every V1–V12 SQL byte is retained.

V13 introduces `server_principal(id,kind,role)` as an integrity registry for the two separate root namespaces. It is not an anonymous identity, installation mapping, authorization decision or history merger. The current directory still distinguishes the actual account stores, checks ambiguity and current revocation. Registry rows have no phone number, proof, credential or new personal data beyond existing ID/role and namespace.

- Administrative adoption locks both root tables with a five-second lock budget, rejects overlapping IDs before any registry creation, and backfills IDs/kinds/roles without updating roots or existing business rows/receipts. All pending SQL/history is transactional.
- Fixed root-table triggers maintain the registry in the same INSERT/UPDATE/DELETE transaction. IDs and roles cannot change; legacy display-name updates retain their behavior. Concurrent inserts of the same ID in different namespaces cannot both commit.
- A migration-owner SECURITY DEFINER trigger function uses the fixed `pg_catalog,pg_temp` search path, schema-qualified relations and bound values. Its only public entry is the installed root triggers; PUBLIC/client/runtime roles receive no EXECUTE. The runtime can read the registry but cannot insert, update, delete, truncate, reference or maintain it directly. Runtime trigger execution is tested with no function EXECUTE privilege.
- The existing dose FK is replaced with an immediate validated NO ACTION reference to this registry. It accepts both identity kinds and preserves the refusal to delete a principal with retained dose receipts. No cascade, receipt rewrite, identity renaming, baseline or repair is introduced.
- Startup validates exact V1–V13 history, private registry privileges, trigger/function metadata, immediate non-cascading FK and exact registry/root consistency in a read-only repeatable-read transaction. It never repairs or populates the registry.

## Separate administrative adoption

This change contains no online migration, role grant, Render mutation, Storage/RLS update or actual provider/SMS/device operation. Use the separate version-matched administrative distribution, private migration credentials, reviewed backup/restore plan and the same primary selected by #155. Stop incompatible writes during adoption. Do not apply a prototype initializer or turn startup into a migrator.

S2 must inventory existing history/ID collisions/default grants and coordinate #147/#144/#150/#155/#145 plus the cloud/index prerequisites. A conflicting old index-V10 history or an existing manual registry needs a separately reviewed recovery plan. Ambiguous IDs are never automatically merged or assigned to a winning namespace.

After migration, use the actual reviewed role/schema (psql identifier variables), retaining separately reviewed existing phone/P0 grants:

```sql
BEGIN;
REVOKE ALL ON TABLE :"schema".server_principal FROM :"runtime_role";
GRANT SELECT ON TABLE :"schema".server_principal TO :"runtime_role";
REVOKE ALL ON FUNCTION :"schema".server_principal_sync() FROM :"runtime_role";
COMMIT;
```

Check inherited column/table privileges and implicit all-data roles as well as direct grants. The phone runtime must not be a member of predefined all-data/maintenance roles, including non-inheriting SET-capable membership. The trigger function must have owner-only EXECUTE; remove unexpected default grants through reviewed administration. Do not grant the runtime migration-owner membership or broad schema ownership. Root/registry tables remain backend-private; do not disable RLS on unrelated Storage/API tables.

V13 is forward-only. Application reversion does not delete registry/account/receipt history or downgrade exact release history. A V12-only exact-history application cannot boot against V13. Retain all keys, identity roots, original receipts and registry metadata; restore-and-forward requires an explicit reviewed recovery decision because restoring a pre-V13 backup loses later state. No production rollout is claimed.

## Evidence and remaining #118 work

`BoundPhoneRuntimePostgresTest` exercises actual Boot/HTTP with a restricted runtime and formal migrations: original edit/stop receipts through Server restart/re-login, cross-installation409, current session401 and relation404, phone Elder403, malformed/bad-bearer rejection, and unchanged receipt/revision on failures. Its pairing/photo/confirmation setup is explicitly an administrative fixture for still-closed paths; it inserts no anonymous row for either phone principal. It does not claim those HTTP paths or a full physical journey work.

Migration controls first demonstrate the V12 phone-Elder dose FK failure, then V13 acceptance, unchanged original receipt JSON/xmin and deletion protection; atomic lifecycle, collision/refused adoption and concurrent cross-namespace inserts use real PG. Drift controls cover client/public column access, runtime registry writes/maintenance, function EXECUTE/search-path changes, disabled triggers, orphan rows and missing/cascading FK, without repairing history. The dose rows are structural FK fixtures, not claimed accepted dose HTTP requests. `BoundPhoneBearerFilterTest` proves invalid headers/signatures/profiles cannot reach the body/business chain and no bearer credentials/context survive the request. Existing #145 same-connection/domain tests and legacy controller tests remain.

Full #118 still requires actual S2/C1 review and main adoption; pairing creation/consent, plan confirmation/reads, photo access, occurrence/dose/history and other PS2 current-actor transactions; trusted date input and formal client hosts; managed DB/Storage/RLS adoption; verified Render proxy/instance topology; finalized facts and HTTPS privacy policy; exact signed APK and real supplier/SMS/physical-device acceptance. This C slice does not close those gates.
