# Current plan: Phase 0 lab (track A) + v1 implementation (track B)

Rolling plan document: always describes the phase in progress and is rewritten
at phase turnover (history lives in git). Docs map: docs/README.md.

Decision (2026-07-08): tracks run in parallel. Track B is designed to be
completable by coding agents without owner intervention; every step that
genuinely needs the owner is tagged [OWNER]. The one architectural rework risk
accepted: matrix row 10 (Google Photos duplicate-on-rewrite) may change the
write-back strategy; the strategy sits behind an interface, so the blast
radius is one module.

Assumptions in force (confirmed 2026-07-08): namespace
https://ns.engram.cam/1.0/ is frozen (engram.cam registered 2026-07-08,
package renamed to cam.engram); enrichment defaults to keyless providers
(Open-Meteo weather, platform Geocoder places); minSdk 33 pending the target
device list (design A1).

## Track A: verification lab (owner-gated, checklists ready)

- A1 [OWNER] Corpus drop into lab/corpus/ per its README (10 minutes of adb).
  Unblocks: CorpusSmokeTest, landmine verdicts 1 and 2.
- A2 [OWNER] Survivability matrix rows 1 to 11 (lab/survivability-matrix.md):
  run corpus files through each path on the Pixel, then
  `engram verify --json` against the sidecars; paste verdicts.
- A3 Write-back spike app: agent builds it (part of track B milestone M4);
  [OWNER] runs it on the Pixel and reports the row 10 and row 11 verdicts plus
  consent UX and write latency.
- A4 [OWNER] Russian transcription spike (design D15 gate): agent ships a
  debug screen in M3 that records and transcribes ten natural notes; owner
  speaks them and judges quality. Fallback already designed: audio-only v1.
- A5 Landmine register verdicts (previous section 0.5 list, unchanged) get
  recorded in lab/survivability-matrix.md as they land.

## Track B: v1 implementation milestones (COMPLETE)

All milestones M0 to M8 landed and build green (`./gradlew build` plus
`:core-format:compileKotlinIosArm64`: unit tests, integration tests, ktlint,
detekt, AGP lint, iOS klib tripwire). Verified on an Android 16 emulator
2026-07-08 (KVM now available): onboarding, ingest counts, queue, annotate,
browse, tools, settings all render; the run caught and fixed an onboarding
recompose bug and a settings label layout bug. Definition of done for every
milestone was: build green, CHANGELOG updated, docs touched.

### Known v1 gaps (built vs designed), for the owner to decide

- Transcription: on-device dictation (speech to text) is wired into annotate as a
  note-filling mic, language-selectable and decoupled from the UI language (D15).
  Still open: auto-generating a Transcript record from a recorded voice clip; clips
  are stored as audio only. The A4 ru-RU quality spike still informs whether the
  auto-transcript is worth adding.
- Enrichment covers place + weather (Open-Meteo + Geocoder). Calendar-event
  enrichment (named in D4) is not built.
- Audio codec is Opus only; the "AAC via advanced setting" option (D6) is not
  exposed.
- Distribution (M9): signing config and the tag-driven release workflow now exist
  (D24); the signed APK on GitHub Releases awaits the owner provisioning the keystore
  secrets and cutting the first tag. The landing Download button points at the
  latest-release asset.
- Not built, were nice-to-haves: share-sheet inbound "annotate this" target;
  home-screen widget (widget was already roadmap, not v1).

- M0 Spec draft: write spec/engram-spec-v0.md from the implemented reality
  (record frame with id and writer, bindings, ExtendedXMP, caption mirrors,
  expectation sidecar format). Open under CC BY 4.0 (D18).
- M1 Android scaffold: :app module (Compose, minSdk 33, package
  cam.engram.app), local Android SDK + emulator bootstrap for autonomous
  UI verification, CI extended to assemble a debug APK artifact.
- M2 Ingest and index: Room database (items, records cache, pending queue),
  MediaStore ingest for camera + screenshots buckets (D11), periodic reconcile
  worker, index rebuild action (D3). Robolectric tests.
- M3 Annotate flow: queue pager UI, text input, hold-to-record voice
  (MediaRecorder, Ogg/Opus per D6), playback, crash-safe drafts, plus the
  transcription debug screen for A4.
- M4 Write-back: strategy interface, MediaStore.createWriteRequest batch
  consent, transactional backup-write-verify-restore built on :core-format,
  strip-detection against the last-seen records cache, one-tap re-embed.
  Also: the A3 spike is this module's debug entry point.
- M5 Digest and nudge: evening digest notification (user-set hour), optional
  post-burst nudge (default off, A2), settings screen for both (D12).
- M6 Browse, search, backfill: timeline of annotated media, note history,
  audio playback, full-text search over notes and transcripts, backfill flow
  for any older media.
- M7 Enrichment: Open-Meteo + Geocoder workers deriving from EXIF GPS and
  timestamps, provenance-tagged enrichment records, network toggle (D10),
  written alongside the next user-initiated write session (design sec 8).
- M8 Export and verifier: Engram Archive export to a SAF location, in-app
  backup verifier reusing the cli extraction logic, size-cap warnings (D13),
  RU + EN strings (D20), onboarding screens incl. the backup-honesty page.
- M9 Release prep [OWNER]: signing config + tag-driven release workflow implemented
  (D24), landing button wired to the latest-release asset. Remaining owner steps:
  provision the keystore secrets, cut the first tag (trial with vX.Y.Z-rc1), device QA,
  install on target devices.

Milestone order is dependency-driven: M2 before M3 before M4; M5 to M8 can
interleave after M4. Matrix verdicts (track A) may inject work into M4/M8
wording and behavior; nothing else depends on them.

## Exit criteria for v1 (definition of done, design sec 2)

- Track A: matrix rows 1 to 11 filled; landmine verdicts recorded; A1/A6/A8
  device facts confirmed; transcription verdict recorded (D15).
- Track B: M0 to M8 complete and green; M9 installed on target devices.
- Coverage: per-module Kover floors + aggregate gate green in CI; core-format ~98%,
  cli ~98%, app ~92% (floors 97/97/90), aggregate ~95.4% enforced at 95% (D22).
- engram.cam registered and namespace confirmed (done 2026-07-08).
- The owner and early adopters annotating real photos happily.
