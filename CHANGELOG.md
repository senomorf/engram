# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions will
follow [SemVer](https://semver.org/) once releases exist. Every user-visible
change lands under Unreleased at merge time.

## [Unreleased]

### Fixed

- A lapsed or partial photo permission can no longer make a background sync wipe the app's
  record of what it has seen: the library is only pruned while full "all photos" access is
  held, so an Android 14 "Select photos" grant that expires after the app is backgrounded no
  longer looks like every photo was deleted.
- On Android 14 and later, choosing "Select photos" is now recognized as partial access: the
  queue asks for full "Allow all" access (Engram works across your whole library to find
  photos waiting for a memory) instead of proceeding on an ephemeral subset, and it re-checks
  the grant when you return to the app.
- When Android reassigns a photo's internal id to a different picture without the app ever
  seeing the old one removed, the app no longer scans the new photo under the old one's
  identity: it now re-derives the identity so the old memories stay as their own entry, the
  new photo is indexed on its own, and the previous photo's cached location and any unsaved
  draft are dropped instead of carrying onto the unrelated new photo (and this cleanup now
  commits together with the re-index, so an interruption partway cannot leave the old draft
  attached to the new photo).
- A save interrupted by the app being killed can now be finished: if restoring the original
  photo needs the storage permission again (Android does not keep the grant across an app
  restart), the app asks for it, on the next launch and from a card in the queue, instead of
  silently failing every retry. The untouched backup is always kept until the restore lands,
  so the photo is never lost, only its restore waits for permission.
- Finishing an interrupted save can no longer overwrite an unrelated photo: if Android has
  reassigned the photo's internal id to a different picture since the save was interrupted,
  recovery keeps that new photo untouched and preserves the old backup on the side instead of
  writing it over the wrong image.
- Adding a caption to a photo whose existing IPTC metadata uses an extended-length field
  no longer silently drops that field and every keyword, credit, or copyright after it:
  such datasets are now carried through byte-for-byte, and if the existing IPTC cannot be
  parsed safely the caption mirror is skipped (leaving the metadata untouched) instead of
  rewriting a truncated copy.
- The in-app backup verify no longer reports a file fully intact when a memory was
  cleanly removed: it now checks how many records survived against how many were saved
  and reports the file as incomplete when some are missing, and a corrupt PNG image
  (a bad chunk checksum) with a surviving record is reported as damaged rather than
  intact.
- The verify now reports a PNG that was cut off before its final marker (a save
  interrupted partway) as damaged instead of intact, even when every embedded memory
  chunk is present: a structurally incomplete image is no longer treated as a clean file.
- A single record frame with a malformed writer id (invalid bytes that expand past the
  size limit when decoded) no longer throws and aborts reading every record behind it:
  the reader now surfaces such a frame as an opaque one and carries on, so verify, cache
  repair, and archive export cannot be wedged by one hostile or corrupt frame.

- Navigating away or rotating the phone mid-recording no longer leaves the microphone
  running invisibly: the annotate screen now stops the recorder whenever it goes away,
  the captured clip survives as a draft, a recorder failure no longer leaks the native
  recorder, and a failed stop no longer leaves a partial clip file behind.
- Archive export no longer runs on the UI thread (a large library could freeze the app
  into an "not responding" dialog) and no longer dies silently when you leave the Tools
  screen mid-export: the job and its result now survive navigation, and the last run's
  outcome is still shown when you come back.
- The app now actually asks for notification permission (Android 13+ never grants it
  silently), so the default-on evening digest can fire on a fresh install: the request
  appears when onboarding finishes and again when a nudge toggle is flipped on without
  the grant, and Settings shows when notifications are blocked with a button straight
  to the system toggle. Denying never blocks anything else.
- A record whose header is damaged (not just checksum-corrupt: a lost magic byte, a
  truncated header, a length claim running past the data) no longer hides every
  record stored after it in video reads, cache repair, and archive export; the
  reader now carves the remainder and finds the survivors.
- engram verify no longer reports a file intact when damage sits outside the planted
  note or audio: a damaged record carrier or a stray corrupt record fragment now
  degrades the verdict, and a lost historical record (the sidecar now lists every
  planted record id) damages it. Legitimate appends after the sidecar still verify
  intact, and old sidecars keep working by record count alone.
- archive validate now proves completeness in both directions: a manifest that
  inventories nothing (or omits a file that exists) no longer vouches for a populated
  archive, duplicate and path-escaping names are refused, item documents must
  reference inventoried files, and the engram archive marker is required. Validating
  a directory holding foreign files now fails by design.
- A save refused before the photo is ever opened (a motion photo, an unsafe layout,
  oversized metadata) no longer demands a restore it cannot perform: the file is
  reported untouched, no backup lingers, and the item no longer wedges every later
  save behind "previous write unresolved" until an app restart with storage access.
- Android reusing a photo's internal id for a new capture can no longer destroy the
  old photo's cached memories or export them under the new photo's name: the
  recovery cache is now keyed by capture, the displaced memories survive as their
  own archive entry, and existing caches migrate in place.
- Memories cached by early builds that never stored a content hash are no longer
  stranded: the index backfills the hash on the next scan while the photo is still
  around, and if the photo is already gone the memories still export, named by
  their record log's own hash and marked sourceHashKnown:false instead of being
  dropped as failed.
- Retrying a save after a badly failed one can no longer delete the only recoverable
  copy of the photo: a new save first settles the previous attempt (restoring the
  original from its backup when needed) and refuses to start if it cannot, and a
  retry after an interrupted cleanup no longer drops the records the earlier save
  had already embedded.
- The transcription lab now honors the remote-dictation consent setting instead of
  silently opting in: on a device without an on-device speech model it shows the same
  disclosure as the note screen before any audio can reach a network recognizer, and
  its status line no longer claims on-device recognition when only a network
  recognizer exists.
- Exporting two identical copies of the same photo no longer silently overwrites
  one memory set with the other in the recovery archive: rows with the same content
  hash merge into one entry carrying every record, and the export count now means
  distinct archive entries.
- Notes typed while a save is in flight are no longer silently lost: the annotate
  inputs freeze until the save resolves, saving is blocked while a recording is
  still being captured, and discarding audio cannot delete a clip the in-flight
  save is reading.
- A save is now verified by the exact records it wrote, not by a bare record count:
  a write the storage provider silently dropped can no longer report success on the
  strength of records left over from an earlier save.
- A corrupted record can no longer hide the intact records stored after it: a record
  whose checksum fails has no say over how far it claims to extend, so the reader
  resynchronizes and finds every intact record behind it (in reads, repair, verify,
  and archive export alike).
- Records written by a future Engram version now survive re-embeds and strip repair
  instead of disappearing: the reader surfaces them as opaque frames (like unknown
  kinds) rather than stopping at the first one, and the frame envelope is now frozen
  in the spec so future versions stay recognizable. A maliciously crafted record
  header can no longer crash the reader.
- A save interrupted while indexing can no longer mark a memory as indexed while the
  recovery cache misses it: the media row, the recovery cache, and the search index
  now commit together, fed by the same verification scan that approved the write.
- Saving a new memory no longer shrinks the strip-recovery cache when the file had
  earlier lost records the cache still held: every index path now merges the cache
  as a superset, atomically per item, keyed by each record's id and checksum.
- Repairing a stripped file no longer duplicates the records that survived: repair
  appends only the missing frames, byte-exact, and now also restores files whose
  cache holds only future-format records. Crash recovery counts carried
  future-format frames toward a completed write instead of rolling it back.
- Weather enrichment no longer invents a "clear" reading when the provider returns
  fewer weather codes than temperatures; malformed responses are skipped instead.
- The backup verifier reports damage more honestly: media that no longer parses is
  "could not read" instead of "nothing survived", a record container that lost
  frames counts as damaged even when the remaining records read back, and
  record-like bytes hiding inside photo metadata no longer count as survived
  memories. Verifying a video now streams it instead of loading it whole.

### Security

- The Claude CI workflows now run only for the repository owner (any other account
  mentioning @claude or opening a pull request no longer spends the repository's
  credentials), and their actions are pinned by commit SHA like every other
  workflow.
- The signed-release workflow no longer trusts the pushed tag name: it is validated
  against a strict version format (any tag carrying shell metacharacters is rejected
  before the signing key is ever decoded) and the version flows into the build and
  publish steps as quoted environment variables instead of being expanded into the
  shell command, closing a path where a crafted tag could have run code with the
  signing secrets in scope.

### Added

- Engram Archive export now writes a byte-exact record log per item (the exact
  frames, future-format ones included, readable by any conforming decoder), and the
  archive manifest inventories every file with its checksum so an archive can be
  proven complete. Memories whose records are all future-format are exported too
  instead of being skipped.
- New cli command `engram archive validate --in <dir>`: proves an archive complete
  and readable (every inventoried file present with its checksum, every record log
  fully decodable with counts matching the readable view, every audio blob present).

### Changed

- Archive identity hardened: entries are named by sha-256 (was md5) so the name can
  double as an import identity key, videos are content-addressed by streaming (a
  scan or export never loads a video whole), entries carry the photo's real file
  name instead of its folder, and each export writes into its own fresh directory
  so runs never mix.

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
