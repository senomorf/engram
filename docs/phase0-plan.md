# Engram Phase 0: verify before building

Goal: convert the load-bearing assumptions into test results before implementation
starts. No product code. Only the format harness (which seeds the real engine),
one throwaway Android spike, the transcription spike, and the spec outline. All
platform claims date to Jan 2026 knowledge and must be re-verified against current
OS and service behavior.

## 0.1 Format harness: corpus generator + verifier (build first)

JVM Kotlin CLI, pure Kotlin core (no android.*). Not throwaway: it becomes
:core-format and the reference CLI.

- `generate`: given JPEG/PNG/MP4, write (a) standard fields: XMP dc:description,
  EXIF UserComment, IPTC caption; (b) large payload via ExtendedXMP (JPEG) or
  iTXt (PNG); (c) binary audio record (magic EGRM + length + CRC32) as JPEG
  post-EOI trailer, PNG private safe-to-copy chunk, MP4 custom top-level box;
  repair MPF offsets when present.
- `verify`: given a file, report which planted payloads survived (exact /
  partial / gone) plus structural report: segment or chunk or box map, trailer
  map, MPF offset validity, Motion Photo directory validity.
- Corpus: plain JPEG; Pixel 9 Ultra HDR JPEG (default camera output); Pixel 9
  Motion Photo; Pixel 9 screenshot (PNG); plain MP4; one camera photo and one
  screenshot from EACH family/friend device model (Samsung SEF trailers and
  OEM screenshot formats vary); one HEIC sample (shelved, generator may skip).
- Golden-file unit tests from day one; byte-exact expectations.
- Audio payloads in tests: Ogg Opus (app default) and AAC m4a (alternative).

## 0.2 Survivability matrix

For each path: run corpus through, then `verify`. Cells: survived / transformed /
stripped, per payload kind (std caption, ExtendedXMP or iTXt, binary records,
MPF intact, Motion still plays).

Paths, Pixel 9 + accounts we control:

1. Control: local copy, Files app copy, Syncthing round trip (expect identical)
2. Google Photos backup, Original quality: download via web UI and via Takeout
3. Google Photos backup, Storage saver: download (also: does PNG get converted?)
4. Google Photos in-app editor: trivial crop, save (both save modes if offered)
5. Pixel Markup editor: save
6. WhatsApp: send as photo; send as document
7. Telegram: send as photo; send as file
8. Signal: send as photo
9. Gmail: attach and send
10. After our in-place rewrite of an already-backed-up photo: does Google Photos
    re-upload it as a duplicate (hash change), replace, or ignore?
11. Motion Photo after our write: does motion playback still work in Google
    Photos?

Deliverable: the matrix, committed as design doc appendix A. Ground truth for
every survivability claim the product makes, and the source of the in-app
backup warning wording.

## 0.3 Write-back spike on Pixel 9 (throwaway app allowed)

Minimal Android app:

- Pick newest camera photo, request write consent (MediaStore.createWriteRequest),
  rewrite in place via fd using backup-then-write-then-verify pattern (no atomic
  rename exists for media we do not own; partial write must never destroy a
  photo).
- Answer: consent dialog frequency (once per batch?), write latency on ~12MP
  Ultra HDR files, Google Photos sync behavior afterwards (feeds matrix row 10),
  Ultra HDR rendering after MPF fixup, Motion playback after trailer coexistence
  (row 11), Screenshots bucket detectability via MediaStore on Pixel and on
  family devices (assumptions A6, A8).

## 0.4 Spec v0 outline (private until stable)

Logical model per design doc section 6: append-only record log (note, audio,
enrichment), id + kind + schema version + timestamp + writer id + CRC32 per
record, dual-write rule, current-memory view over history.

To pin down in the outline:

- Identity: XMP namespace https://ns.engram.photos/1.0/ (after registration),
  record magic EGRM, size budget defaults (soft cap ~10MB, D13).
- JPEG binding: XMP APP1 + ExtendedXMP; binary records after EOI; MPF (APP2)
  offset repair; Motion Photo coexistence rules, including the legacy
  EOF-relative MicroVideoOffset problem.
- PNG binding: XMP iTXt; private ancillary safe-to-copy chunks for binary
  records; chunk naming per PNG rules.
- MP4 binding: one custom top-level box; no moov rewrite in v1.
- Engram Archive export schema (manifest + per-item JSON + audio blobs).
- Audio: Opus in Ogg default, AAC-LC alternative, codec field open (D6).

## 0.5 Landmines register (each gets a verdict in Phase 0)

1. Ultra HDR (JPEG_R) is the DEFAULT Pixel 9 output; gain map located via MPF
   offsets that our insertions shift. Unrepaired, photos silently lose HDR.
2. Motion Photo coexistence (default-on for Pixels): appending after Google's
   video trailer vs updating the XMP Container directory; what does Google
   Photos playback tolerate?
3. Google Photos hash-change duplicate on in-place rewrite of a backed-up photo.
4. Storage saver recompression: assumed to kill trailers; confirm; check PNG
   conversion too; product must warn.
5. No atomic replace through MediaStore write requests: transactional pattern
   required, crash-tested.
6. ExtendedXMP support in the wild is spotty: dual-write of standard fields is
   the compatibility floor; verify Google Photos and Apple Photos display them.
7. Play Console: personal accounts need a closed test (12 testers, 14 days)
   before production access (verify current rule; v2 concern). Internal testing
   track for v1: no such gate, install by link.
8. Google Photos Library API (since March 2025): app can only access items it
   created. No "repair cloud copies" feature can exist; do not design one.
9. Screenshots bucket names and formats vary by OEM (PNG on Pixel, JPEG option
   elsewhere); verify per family device (A6, A8).
10. PNG chunk survival through editors and Google Photos is untested territory;
    matrix covers it (screenshots are edited rarely, but verify anyway).

## 0.6 Russian transcription spike (gates D15)

On Pixel 9: on-device SpeechRecognizer with ru-RU. Measure availability of the
on-device model, accuracy on natural family-style speech (10 sample notes),
latency. Verdict options: transcripts in v1; transcripts deferred to v1.x
(audio-only v1); or bundled-model alternative goes on the roadmap. Audio is
recorded and stored in every scenario, so no data is lost by deferring.

## Exit criteria

- Survivability matrix filled for rows 1 to 11.
- Spike answers: consent UX, GP duplicate behavior, Ultra HDR intact, Motion
  intact, Screenshots bucket detection.
- Transcription verdict recorded in design doc (D15 resolved).
- engram.photos registered; namespace URI frozen; package prefix photos.engram
  confirmed.
- Family/friends device list collected; A1 (Android 13+), A6, A8 verified.
- Spec v0 outline reviewed and frozen enough to start :core-format.

Then: implementation planning against design doc v1.
