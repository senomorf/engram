# Engram roadmap

Record of intent, not commitment. Ordering is rough; nothing here constrains v1
(docs/design.md section 13 defines v1). Items graduate into the design doc when
they get scheduled.

## v1.x (after family adoption, small increments)

- Bake-out sharing: caption burn-in for stripping destinations; photo plus voice
  note rendered as a short video; share-as-file shortcuts with disclosure.
- Home screen widget (one-tap into the annotate queue).
- Folder allowlist for sources beyond camera and screenshots.
- Scheduled automatic Engram Archive export (v1 export is manual).
- Transcripts backfill, if the Russian on-device gate deferred them from v1.
- Backup verifier improvements (guided flows per destination).
- Offline reverse geocoding (drop the network dependency for place names).

## v2 (bigger features, still Android)

- Memory sessions: explicitly armed start/stop ambient capture for an outing,
  visible-recording UX (persistent notification, obvious indicator). Android
  only by design.
- HEIF/HEIC binding.
- Encrypted Engram Archive export option.
- Play production release: personal dev accounts require a closed test
  (12 testers, 14 days) before production access; verify the current rule then.
- Spec publication plus reference CLI release (license per D18).
- F-Droid distribution, if the project is public by then.

## Far, explicitly gated

- iOS port. Gate: real demand from an iPhone user we care about, plus a
  re-evaluation of PhotoKit constraints at that time, plus access to an iPhone
  for testing. Guardrails that keep this possible are design doc section 10.
- Shared family pool: one photo set annotated by several people (synced folder
  or shared album). Multi-writer merge and sync semantics; the format already
  carries writer id so no migration is needed, everything else is new work.
- Web viewer: read-only Engram reader in the browser (drop a file, see and hear
  the memories). Natural companion to spec publication.
- DNG/RAW binding, if anyone in the audience shoots RAW.
- Ambient AI companion: reopens only if platform rules (background microphone),
  consent law exposure, and on-device model quality all shift materially.
  Constraints research lives in design doc appendix C.
