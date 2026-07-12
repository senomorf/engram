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
      increased) and existing indexed media plus saved memories survive. This exercises the
      record_cache schema migration to v3; a launch crash here means the migration failed.

## Permissions

- [ ] Media (photos/videos), notifications, microphone (voice), and location (preserves a
      photo's GPS on annotation, and enrichment) prompts appear when first needed.
- [ ] Denying each is handled gracefully (feature degrades, no crash); granting later works.
- [ ] Partial media access (Android 14+, finding H5): choose "Select photos" at the media
      prompt; the queue shows the "Allow all photos" steer instead of proceeding, and picking
      "Allow all" then lets it in. Background the app until the partial grant lapses and return:
      the queue re-checks and re-steers, and no seen media is dropped (the index is not pruned
      without full access). Confirm a background reconcile on a partial/denied grant never
      empties the library.

## Core flows

- [ ] Ingest: camera and screenshot media show up in the annotate queue.
- [ ] Text note: add and save a note; it writes back into the file with MediaStore consent.
- [ ] Voice note: hold-to-record an Opus clip, save, and play it back in the detail view.
- [ ] Recorder lifecycle (finding R7): rotate the phone mid-recording, then navigate away
      mid-recording; both times the mic indicator clears immediately and the captured
      clip survives as the draft chip. An instant tap-release (no audio captured) leaves
      no stray file and the next recording works.
- [ ] GPS preserved (finding 1): with location granted, annotate a camera photo that has
      GPS, then confirm the saved file still carries its location (Google Photos map, or
      `exiftool -gps*`). Deny location and confirm the save warns before removing it.
- [ ] Crash recovery consent (finding C2): interrupt a save (kill the app mid-write, or seed
      a pending backup) so the target is left damaged, then relaunch; the app asks for storage
      permission to finish restoring the photo, and the queue shows a "finish restoring" card
      if the launch prompt is dismissed. Granting restores the original; the backup is never
      lost while it waits.
- [ ] Dictation consent (finding 6): on a device with no on-device speech model, tapping
      dictate shows the network-dictation disclosure; enabling it (or the Settings toggle)
      lets it work, and the toggle revokes it. With an on-device model, no prompt appears.
- [ ] Lab consent (finding C): on the same modelless device with the setting off, Lab's
      Speak shows the disclosure instead of reaching the network recognizer; enabling once
      persists to the Settings toggle. The Lab status line reports on-device availability
      truthfully.
- [ ] Survivability (the core promise): send an annotated file through a messenger or
      cloud that strips metadata, pull it back, and run the in-app backup verifier (or
      `engram verify`); confirm the memory survives, or degrades exactly as expected.
- [ ] Browse timeline, full-text search over notes and transcripts, memory detail with
      note history and audio playback.
- [ ] Enrichment (opt-in: turn it on in Settings first, which discloses the GPS it sends):
      place (Geocoder) and weather (Open-Meteo) attach on the next save.
- [ ] Digest and nudge: evening digest notification at the set hour; post-burst nudge.
- [ ] Notification permission (finding R10, D30): on a fresh install the permission
      prompt appears when onboarding finishes; denying it surfaces the Settings hint row
      whose button opens the system notification toggle; granting there makes the digest
      fire. Flipping a nudge toggle on without the grant re-asks.
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
