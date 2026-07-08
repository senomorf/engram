# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions will
follow [SemVer](https://semver.org/) once releases exist. Every user-visible
change lands under Unreleased at merge time.

## [Unreleased]

### Added

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
- Tooling: Gradle 9.6.1 wrapper, Kotlin 2.3.20, version catalog, ktlint +
  detekt, integration test suite, GitHub Actions CI, dependabot, dev container.
- Adversarial review round 1 (Codex) outcomes: record frame carries id and
  writer; XMP merge fails closed and supports ExtendedXMP split/reassembly;
  writer refuses Motion Photos and MPF-unsafe layouts; IPTC and MP4 caption
  mirrors; real verify command with .engram-expect sidecars and --json;
  MP4/PNG/MPF parsing hardened; iosArm64 klib tripwire in CI.
