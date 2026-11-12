# S2 photo file validation preparation — #13 / T06

Historical preparation record on main `64ddd4e26440d60a60f209dd7eb12d102e2a55dd` (2026-10-25).
The original preparation below later became [Draft PR #61](https://github.com/BRMRs/group33/pull/61). It is not completion/Ready of #13.
The current #56 branch is unchanged. The local patch contains two Kotlin files
and necessary evidence/contribution/manifest records only.

`PhotoInputValidator` checks bounded file bytes, parses the declared media type,
detects the actual JPEG/PNG reader, and checks dimensions with Long arithmetic
before full pixel decoding. It does not accept extension/MIME alone as evidence.
The limits are 5,242,880 bytes inclusive and 20,000,000 decoded pixels inclusive.
The caller must supply file bytes, not the whole multipart envelope.

PNG checks include bounded chunk lengths, CRCs, ASCII-letter chunk names, first
IHDR, consecutive IDAT and a complete final IEND. Full decoding is still required;
CRC-correct but invalid compressed pixels fail. JPEG decoder repair/warnings are
conservatively rejected. Ordinary EXIF, progressive/grayscale JPEG and common PNG
variants are tested. This policy and actual-device image compatibility require
review before production integration; it is not a universal codec conformance,
malware, animation, per-request CPU or concurrency/memory-admission guarantee.

Accepted results own the exact original bytes and SHA-256. Both input and output
copies are isolated from caller mutation; images are not re-encoded and decoded
rasters are not retained. Each call owns/disposes its reader and uses an explicit
memory input stream, without changing global ImageIO settings or caching photos
to temporary files. No photo bytes, tokens or raw decoder diagnostics are logged.

The four Server-private rejection reasons map to existing error-code families:
TOO_MANY_BYTES → PHOTO_TOO_LARGE, UNSUPPORTED_TYPE → PHOTO_TYPE_UNSUPPORTED,
INVALID_IMAGE/TOO_MANY_PIXELS → VALIDATION_FAILED. Future HTTP adapters must map
413/415/422 and scoped fields under the accepted API; those responses have not
been implemented/tested here. No new Shared/wire fields or field codes are added.

22 targeted real-code tests pass locally. Two added PNG structural counterexamples
failed before their fix and pass afterward without relaxing rejection assertions;
legal consecutive IDAT remains accepted. Fixtures are programmatically generated,
not real medical photos. Run `./gradlew :server:test --tests '*PhotoInputValidatorTest'`
and standard Shared/Server regression plus `gate1Check test`, policy and hashes.

Deferred: upload authentication/authorization, streaming/multipart request limits,
Storage staging/READY/cleanup, receipts/transactions, HTTP, database and devices.
Scope/reviewer confirmation and final-head CI remain necessary before publication.

References: [API/T06](../API_CONTRACT.md#photo-to-plan-transaction),
[PNG structure](https://www.w3.org/TR/png-3/#5Chunk-layout),
[PNG ordering](https://www.w3.org/TR/png-3/#5ChunkOrdering),
[JDK ImageReader](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/imageio/ImageReader.html),
[memory stream](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/imageio/stream/MemoryCacheImageInputStream.html).

## PR #61 review correction — 2026-10-27

Responding to [the blocking PNG review](https://github.com/BRMRs/group33/pull/61#issuecomment-6438778318)
on original head `a900f42c90e465158572edfa7ea9a05709994705`:

- Added and actually ran two CRC-correct, zero-length chunks immediately after IHDR
  in a programmatically generated RGB PNG: critical `AbCD` and ancillary `abCd`.
  Before the production change, 2 cases executed: the critical rejection assertion
  FAILED because the validator returned Accepted; the ancillary case PASSED.
- Local runtime: JetBrains OpenJDK/JBR `17.0.14+1-b1367.22`, built-in PNG provider
  `com.sun.imageio.plugins.png.PNGImageReader`. This is actual local reproduction,
  not a claim to have executed on CI's different JDK build.
- Independently reject unknown critical chunks while retaining recognized IHDR,
  PLTE, IDAT and IEND. The decoder and its warning listener remain unchanged.
  Unknown ancillary chunks retain existing handling. The ancillary control verifies
  byte-for-byte original content, SHA-256 and decoded dimensions.
- After the fix, all 24 photo validator tests actually executed and passed, including
  the original 22 cases unchanged. Full `gate1Check test --offline --console=plain`
  passed: Server 64, Shared 18, Android Debug/Release 40 each (162 reports, zero
  failures/errors/skips). Server and both Android test variants executed; Shared
  came from cache. Lint had 0 errors and 16 existing warnings; bootJar/APK,
  repository policy, whitespace diff and all 130 artifact hashes passed.
  Exact final commit and its independent CI are recorded in the PR after push.
  No upload/HTTP/Storage scope is added.

Reference: [PNG §13.1 error handling](https://www.w3.org/TR/png-3/#13Error-handling).
This targeted correction is not full PNG conformance, actual-device acceptance,
formal review approval or a merge.
