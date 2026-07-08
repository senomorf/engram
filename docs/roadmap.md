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

## Android platform modernization

Already current: Material 3 with dynamic color, edge-to-edge, per-app locale,
themed monochrome icon, predictive-back opt-in, Android 12+ backup rules (D21).
Deferred, rough order:

- Adaptive layouts: window size classes for tablets and foldables (phone-only today).
- Material 3 Expressive: deferred until stable. MaterialExpressiveTheme and the
  expressive tokens are internal in stable material3 1.4.0 (our compose-bom line)
  and public only in 1.5.0-alpha (alpha23 as of 2026-07), so adopting now means an
  alpha dependency across the whole UI. Trigger: material3 1.5.0 stable, then
  MaterialExpressiveTheme slots over the current color scheme in one pass and the
  brand/dynamic toggle carries over unchanged. A hand-tuned shapes+typography
  approximation on 1.4.0 is possible but throwaway, so skipped.
- Predictive-back in-app previews: PredictiveBackHandler with screen transitions (the
  manifest opt-in is in; cross-screen preview animation is not).

## v2 (bigger features, still Android)

- Memory sessions: explicitly armed start/stop ambient capture for an outing,
  visible-recording UX (persistent notification, obvious indicator). Android
  only by design.
- HEIF/HEIC binding.
- Encrypted Engram Archive export option.
- Play production release: personal dev accounts require a closed test
  (12 testers, 14 days) before production access; verify the current rule then.
- Spec publication plus reference CLI release (license per D18).
- F-Droid: dropped. PolyForm Noncommercial (D18) is not FLOSS, which F-Droid
  requires, so the app is ineligible; revisit only if the code license changes.

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
