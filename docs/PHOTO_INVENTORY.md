# Read-only reverse photo inventory

Refs #13 / #16. This separate local operator command adds object-directory-to-database observations to the forward [photo reconciliation](PHOTO_RECONCILIATION.md) command. It is outside the application bootJar and has no HTTP endpoint, Spring bean, startup hook or scheduler. It does not write business data, change permissions, create schema history, delete files, acquire cleanup leases or connect to Supabase. Issues13/16 remain open.

## Build and private input

Use the existing JDK17/dependency cache:

```sh
./gradlew :server:photoReconciliationDist --offline
```

Extract `server/build/distributions/group33-photo-reconciliation.zip` into a private operator directory. The original `bin/photo-reconciliation inspect` command is unchanged. Invoke `bin/photo-inventory inventory` with privately supplied environment variables; do not put real values in Git or shell history.

| Variable | Meaning |
|---|---|
| `GROUP33_PHOTO_INVENTORY_DATABASE` | Explicit local PostgreSQL database name |
| `GROUP33_PHOTO_INVENTORY_PORT` | Explicit local port; host is fixed to127.0.0.1 |
| `GROUP33_PHOTO_INVENTORY_SCHEMA` | Existing selected application schema, excluding public/pg_* namespaces |
| `GROUP33_PHOTO_INVENTORY_USER` | Dedicated non-owner SELECT-only operator |
| `GROUP33_PHOTO_INVENTORY_PASSWORD_FILE` | Existing private regular password file, no final symlink/group/other permissions |
| `GROUP33_PHOTO_INVENTORY_OBJECT_ROOT` | Existing trusted local service-account POSIX directory, mode0700, no final symlink |
| `GROUP33_PHOTO_INVENTORY_BATCH_SIZE` | Optional1–100 object identities per query, default25 |
| `GROUP33_PHOTO_INVENTORY_MAX_ENTRIES` | Optional1–1000 directory entries, default100; at most one additional lookahead |
| `GROUP33_PHOTO_INVENTORY_MAX_MILLIS` | Optional1–30000ms monotonic cooperative work budget, default30000 |

Unknown variables under this prefix are rejected, including HOST/URL or deletion options. App/database/cloud configuration is not used as a fallback. Common private-input validation reuses the existing inspection settings; no new dependency download is needed.

Linux uses JDK `SecureDirectoryStream` for handle-relative no-follow metadata. A JDK without that interface, including the tested macOS JDK, additionally needs an existing Python3 on PATH supporting `scandir(fd)` and `stat(dir_fd=..., follow_symlinks=False)`. The packaged standard-library helper opens the root with O_DIRECTORY/O_NOFOLLOW and retains that descriptor. Its private pipe carries bounded classification data, never file content; its environment omits database credentials. It creates no files or bytecode cache and exits when the command closes. Missing/unsupported helpers fail as incomplete, rather than switching to path traversal. The normal Server runtime does not require Python.

## Scope and reference observations

Only the immediate object directory is enumerated: fixed recursion depth0. Canonical lowercase UUID `.blob` regular private files are matched against the primary database snapshot. `.upload-*.part` private regular files are temporary observations, with no assumed operation ownership. Subdirectories, symlinks, FIFOs, other special files, noncanonical names and public/unreadable-mode files are unsafe observations; they are not followed or opened. The inventory reads attributes only, not photo bytes or hashes.

One read-only Repeatable Read transaction covers every bounded ID batch. The selected schema must expose `medication_photo`, `photo_upload_operation` and `medication` to the SELECT-only non-owner. The tool refuses schema ownership/CREATE, privileged administrative roles, table or column write privileges, and active row security hiding one of these relations. Qualified identifiers and pg_catalog search_path avoid implicit routing. Lock/statement budgets are1s/3s; JDBC connect/socket budgets are3s/5s. A missing or denied reference table fails even for an empty directory.

Each matching object has one observation, in this conservative order:

| Report count | Observation in this database snapshot |
|---|---|
| `stagingProtected` | Stable object identity retained by a STAGING upload reservation |
| `rejectedProtected` | Retained identity/rejection evidence for a REJECTED upload |
| `referenced` | Published photo metadata, an accepted original photo receipt, or a Medication photo reference, including inactive Medication |
| `unmatchedCandidates` | No match in the selected relations; **not certified orphaned or deletable** |
| `temporary` | Recognized private upload temporary-file name, with no deletion eligibility inferred |
| `unsafe` | Entry cannot safely be classified as a private canonical object/temporary file |

Published metadata, upload/receipt identity and immutable Medication references also retain the normal schema's plan/history linkage. This is an inventory against the declared existing schema, not a search through arbitrary JSON, unknown future tables or other environments.

Metadata reads cannot authorize cleanup: database and directory observations are not one atomic snapshot. An upload may start between snapshot creation and directory opening. Directory identity/mtime and each entry's attributes are rechecked; detected replacement, entry mutation or directory change returns CONCURRENT_CHANGE. These checks cannot prove all concurrent activity absent, especially on a coarse-timestamp filesystem. COMPLETE means the selected directory iterator exhausted within its bounds, not a globally frozen inventory. Both `globallyConsistent` and `deletionAuthorized` are always false.

## Output, failures and rollback

Output is one aggregate JSON line without object IDs, names, paths, accounts, fingerprints, passwords, photo content or raw exception messages. Unavailable source, unknown state, denied access, interruption and truncated scans are explicit incomplete results; their counts describe only observations processed so far. A directory or helper failure is not a healthy empty inventory.

| Exit | Meaning |
|---|---|
| 0 | Selected iterator exhausted, no unmatched/temporary/unsafe observations; protected entries may still exist |
| 2 | Exhausted, with unmatched candidates, temporary files or unsafe observations requiring investigation |
| 3 | Incomplete: limits, interruption, database/storage unavailable, or observed concurrent change |
| 64 | Invalid command/private input; sanitized error only |

Elapsed checks are cooperative between operations, not an absolute kernel-I/O/cleanup deadline. The fallback pipe has a5s response budget; the helper is terminated and reaped on close. Normal metadata/directory access may affect access times. File content, mtime, modes, reservations, business rows and accepted receipts are not modified.

Rollback is removal of this separate operator command/distribution; no application or database rollback is required. Unknown uploads older than24h remain recoverable using their original key and receipt. Actual cleanup still needs a separately reviewed durable lease/claim/fencing and reference-race protocol, deletion-failure recovery and applicable migration. This inventory implements none of those new write semantics and cannot be passed as a deletion plan.

Verification and limits: [S2 reverse-inventory evidence](evidence/S2_PHOTO_INVENTORY.md). No normal-App, real cloud/phone/V2, two-device or disconnected-LAN acceptance is claimed. Preserve the Draft parent #105 and normal non-author security/persistence review before main integration or operational use.
