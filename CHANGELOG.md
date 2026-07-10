# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions will
follow [SemVer](https://semver.org/) once releases exist. Every user-visible
change lands under Unreleased at merge time.

## [Unreleased]

## [0.1.1] - 2026-07-10

### Fixed

- Write-back recovery no longer deletes a memory's backup when a crash leaves the media
  file parseable but missing its records; recovery now confirms the expected records are
  present before clearing the backup.
- The backup verifier no longer reports a file with CRC-corrupted records as fully
  survived: it flags corrupted-but-present memories as damaged across JPEG, PNG, and MP4.
- Engram Archive export names each entry by the media content hash so it stays matchable
  across reinstall, and reports a failed write instead of silently counting it a success.
- Records of a kind a newer version might add are preserved through the strip-recovery
  cache and re-embed, so restoring memories can no longer drop them.
- Enrichment degrades gracefully when the device place-name backend errors: it no longer
  stalls, and weather still attaches.
- Annotating a camera photo no longer strips its GPS location: the app reads the original
  (unredacted) bytes instead of the scoped-storage copy.
- Write-back is transactional: a failed or interrupted write can no longer destroy the
  photo or its only backup.
- JPEG writes keep the MPF primary image size in step with the file, so Ultra HDR photos
  stay consistent for viewers.
- Editing a photo caption preserves its other IPTC metadata (keywords, by-line, copyright)
  instead of dropping it.
- The backup verifier reports a file with a mix of intact and corrupt records as damaged,
  not fully survived, shows how many records are corrupt, and no longer claims everything
  survived (it reports only what it can see in the shared file).
- A partial strip no longer shrinks the recovery cache below the records ever seen for a
  photo, so a later repair can restore them.
- Engram Archive export includes memories whose media file was moved or deleted, and fails
  the export if the manifest cannot be written.

### Changed

- Network enrichment (weather and place names) is now opt-in and off by default; the
  setting discloses that enabling it sends the photo's location to an online provider.
- Cloud backup no longer uploads memory content: the record cache, voice drafts and
  write-back backups are excluded so notes and audio stay on the phone.
- Dictation uses the network speech recognizer only after an explicit one-time consent, on
  devices without an on-device speech model; the consent is shown in Settings and can be
  turned off there.

## [0.1.0] - 2026-07-09

### Changed

- Identity moved to the registered engram.cam domain: XMP namespace is now
  https://ns.engram.cam/1.0/ and the package/applicationId is cam.engram
  (was the unregistered engram.photos / photos.engram). Done before any real
  photo was written, so no format migration is implied.
- Target Android 16 (API 37): compileSdk and targetSdk 37, minSdk held at 33.
  Refreshed AndroidX (activity-compose 1.13, lifecycle 2.11), Kotlin 2.3.21,
  AGP 9.2.1 and compose-bom 2026.06.01; Robolectric test runtime pinned to 36.
- Licensing: app and library code under PolyForm Noncommercial 1.0.0 (noncommercial
  use free, commercial rights reserved); the format spec under CC BY 4.0. Not FLOSS,
  so F-Droid distribution is dropped.

### Added

- Automated signed-APK releases: push a version tag to build, sign, and publish the
  APK to GitHub Releases with a SHA-256 checksum and build-provenance attestation; the
  landing page Download button links to the latest release.
- In-app language switch (English and Russian) with per-app locale, plus complete
  Russian coverage across every screen.
- Voice dictation in the annotate flow: on-device, offline-preferred speech to
  text in any supported language, decoupled from the UI language via a per-note
  language picker, fetching a missing on-device model when needed.
- Material 3 polish: standard top app bars via a shared scaffold, Material You
  dynamic color and edge-to-edge.
- Optional mauve brand color scheme, switchable in settings, as an alternative to
  Material You dynamic color (which stays the default).
- Predictive-back opt-in, themed monochrome launcher icon and Android 12+ backup rules.
- Landing page at engram.cam with a favicon and social-share card.

- Android app (Track B, M0 to M8): ingest of camera and screenshot media into
  a Room index; annotation queue with text notes and hold-to-record Opus voice;
  transactional write-back into the media file with MediaStore consent, crash-
  safe backup/restore and strip-detection re-embed; daily digest and optional
  post-burst nudge with a settings screen; browse timeline, full-text search
  over notes and transcripts, and a memory detail view with note history and
  audio playback; EXIF-derived enrichment (place via Geocoder, weather via
  keyless Open-Meteo) written alongside the next save; Engram Archive export to
  a SAF folder, in-app backup verifier, size-cap warning, RU and EN strings,
  three-screen onboarding including the backup-honesty page; a transcription
  lab and write-back spike screen for Phase 0 device checks.
- CLI archive command mirroring the app's Engram Archive export.
- Enrichment record payload (spec v0.2) and the container-agnostic Memory
  reading view, both in :core-format.
- Format spec draft (spec/engram-spec-v0.md).
- Design document (docs/design.md), rolling plan (docs/plan.md), roadmap
  (docs/roadmap.md), docs map (docs/README.md).
- Engram record wire format v0: self-delimiting CRC-protected records (note,
  audio, enrichment, transcript kinds).
- Container bindings: JPEG (XMP merge, ExtendedXMP detection, post-EOI records,
  MPF offset validation), PNG (iTXt XMP, egRm chunks), MP4 (custom uuid box).
- Lab CLI: generate, inspect/verify, selftest.
- Survivability lab templates (lab/corpus, lab/survivability-matrix.md).
- Tooling: Gradle 9.6.1 wrapper, Kotlin 2.3.21, version catalog, ktlint +
  detekt, integration test suite, GitHub Actions CI, dependabot, dev container.
- Adversarial review round 1 (Codex) outcomes: record frame carries id and
  writer; XMP merge fails closed and supports ExtendedXMP split/reassembly;
  writer refuses Motion Photos and MPF-unsafe layouts; IPTC and MP4 caption
  mirrors; real verify command with .engram-expect sidecars and --json;
  MP4/PNG/MPF parsing hardened; iosArm64 klib tripwire in CI.
