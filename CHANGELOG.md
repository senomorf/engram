# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions will
follow [SemVer](https://semver.org/) once releases exist. Every user-visible
change lands under Unreleased at merge time.

## [Unreleased]

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
