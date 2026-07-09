# Device QA checklist

Repeatable manual smoke before promoting a release (milestone M9). Run on a real
target device (Android 13+). The automated emulator smoke in `release.yml` only proves
the APK boots without crashing; this covers the on-device flows and the real platform
adapters (MediaStore, SAF, SpeechRecognizer, MediaRecorder, Geocoder) that the counted
JVM/Robolectric coverage cannot vouch for (design D22).

Record at the end: device model, Android version, app versionName/versionCode, and
pass/fail per section.

## Install

- [ ] Fresh install of `engram.apk` from the release succeeds on a device with no prior
      Engram; app launches to onboarding.
- [ ] Upgrade install over the previous version succeeds in place (versionCode must have
      increased) and existing indexed media plus saved memories survive.

## Permissions

- [ ] Media (photos/videos), notifications, microphone (voice), and location (enrichment)
      prompts appear when first needed.
- [ ] Denying each is handled gracefully (feature degrades, no crash); granting later works.

## Core flows

- [ ] Ingest: camera and screenshot media show up in the annotate queue.
- [ ] Text note: add and save a note; it writes back into the file with MediaStore consent.
- [ ] Voice note: hold-to-record an Opus clip, save, and play it back in the detail view.
- [ ] Survivability (the core promise): send an annotated file through a messenger or
      cloud that strips metadata, pull it back, and run the in-app backup verifier (or
      `engram verify`); confirm the memory survives, or degrades exactly as expected.
- [ ] Browse timeline, full-text search over notes and transcripts, memory detail with
      note history and audio playback.
- [ ] Enrichment: place (Geocoder) and weather (Open-Meteo) attach on the next save.
- [ ] Digest and nudge: evening digest notification at the set hour; post-burst nudge.
- [ ] Export: Engram Archive to a SAF folder; re-open or verify the exported files.
- [ ] Onboarding: all screens including the backup-honesty page render and dismiss.

## Localization

- [ ] Switch language RU <-> EN (per-app locale); every screen is localized, no missing
      strings, app restarts into the chosen language.

## Offline

- [ ] In airplane mode: annotate, browse, search, verify, and export all work; only
      enrichment degrades (best-effort, no error surfaced to the user).

## Release integrity (off-device, once per release)

- [ ] `apksigner verify --print-certs engram.apk` shows your release certificate.
- [ ] `sha256sum -c engram.apk.sha256` passes.
- [ ] `gh attestation verify engram.apk --repo senomorf/engram` passes (needs a recent gh).
