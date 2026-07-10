# Engram design document (v1)

Status: decisions locked via Q&A rounds, 2026-07-06 to 2026-07-08. This is the
finished plan without implementation details. Items contingent on Phase 0
verification are marked [P0] (see docs/plan.md). Future versions live in
docs/roadmap.md. Repo keeps the working name photomemo; product and format are
called Engram.

## 1. Problem and vision

Photos and videos outlive the context that made them matter. Voices, jokes, moods
are gone in a year. Existing fixes (social posts, journaling apps, gallery
captions) store that context in someone else's silo. Engram writes the context
into the media file itself: text notes and voice clips, versioned, carried
wherever the file goes, readable decades later without this app.

Honest ceiling, stated up front: embedded metadata survives file-copy paths
(local storage, originals-quality cloud backup, Takeout, drives) and dies on most
share paths (messengers and social networks strip and recompress). The product
claim is "your archive keeps it forever", never "anything you share keeps it".
Sharing that must carry context uses explicit bake-out (roadmap) or send-as-file.

## 2. Targets and definition of done

- v1 done when: the owner and early adopters use it happily on their own
  phones, installed via a signed APK on GitHub Releases (D17).
- Secondary target: owner learns Android development end to end; all code stays
  reviewable Kotlin, written agentically, gated by tests.
- Explicit non-targets for v1: store production release, revenue, public spec.

## 3. Non-goals (v1)

- No cloud storage of user content, no accounts, no servers.
- No camera app; native camera stays the capture tool.
- No social features; sharing means handing files to other channels.
- No ambient listening (appendix C, research only).
- No iOS build (roadmap, far); no design decision may preclude the port (sec 10).
- No shared multi-person archives (roadmap, far).

## 4. Decision log

- D1 Platform: Android only, minSdk 33 (Android 13+) held for device reach while
  still giving modern APIs (per-app locale, photo picker, dynamic color); compileSdk
  and targetSdk 37. Baseline device Pixel 9.
- D2 Capture: annotator-first. App is a layer over the photo library.
- D3 Data home: file is the single source of truth. App keeps a rebuildable
  index plus a strip-recovery cache: last successfully parsed records per content
  hash, enabling detect-and-re-embed when another app strips the file. The cache
  is the one non-rebuildable store; it is included in Engram Archive export.
- D4 v1 records: versioned text notes + voice notes. Auto-context enrichment
  (place, weather, calendar event) is derived from EXIF GPS and timestamp,
  asynchronously, best-effort, silently absent offline; it never blocks or slows
  the annotate flow.
- D5 Media scope v1: photos and videos; notes attach to the whole file.
- D6 Audio: format is codec-agnostic (records carry MIME). App default: Opus in
  Ogg, mono, speech bitrate. AAC-LC available via one advanced setting. No codec
  choice inside the annotate flow.
- D7 Stack: Kotlin everywhere. :core-format pure Kotlin (KMP-compilable),
  :app Jetpack Compose, :cli JVM tool from the same core.
- D8 Identity: name Engram; domain engram.cam (registered 2026-07-08);
  XMP namespace https://ns.engram.cam/1.0/, now frozen (no real photo
  written yet, so the pre-first-photo rename from the unregistered
  engram.photos was free); package prefix cam.engram, Android applicationId
  cam.engram.app; binary record magic EGRM.
- D9 Dual-write: latest note text mirrored to standard caption fields so
  mainstream galleries display it natively. Implemented today: XMP
  dc:description, IPTC 2:120 caption (APP13), MP4 comment atom (moov-last
  layouts only). Deliberately deferred: EXIF UserComment, because inserting a
  tag into existing camera EXIF shifts IFD offsets and corrupts maker notes
  whose internal pointers no generic rewriter can fix; revisit only with a
  maker-note-safe writer.
- D10 Offline-first: no telemetry, no accounts. Network use is limited to
  best-effort enrichment (weather, geocoding) and, on a device without an
  on-device speech model, remote dictation after explicit one-time consent (D15,
  finding 6); both are off by default and user-toggleable.
- D11 Sources: camera buckets + Screenshots bucket. Screenshots on by default,
  disable in settings. Folder allowlist: roadmap.
- D12 Nudging: both modes built. Evening digest (user-set hour) and post-burst
  nudge. Defaults: digest on, burst nudge off (A2).
- D13 Size policy: soft cap ~10MB embedded payload per file; warn and ask before
  exceeding; never silently delete or compact.
- D14 v1 extras: note/transcript text search; backup verifier (pick a file that
  round-tripped through a cloud or messenger, get a survival report); Engram
  Archive export: all ever-recorded metadata to a user-chosen location in a
  documented parseable format.
- D15 Transcription: on-device (SpeechRecognizer, offline-preferred), integrated
  into the annotate flow as dictation that fills the note field. Recording language
  is decoupled from the UI language: a persisted picker (default the UI language)
  chooses from a supported list, so any supported language dictates regardless of
  UI language; a missing on-device model is fetched via triggerModelDownload. On a device
  with no on-device model, dictation uses the network recognizer only after an explicit
  one-time consent (finding 6).
  Audio is always recorded and stored regardless, so a weak transcript never costs
  the voice clip.
- D16 Archive model: each person annotates their own media on their own phone.
  No sync, no conflicts. Shared pool: roadmap, far. Spec carries writer id
  from day one so that future does not require a format migration.
- D17 Distribution: signed APK on GitHub Releases (install by link), linked
  from the landing page at engram.cam (GitHub Pages). Play
  and F-Droid are deferred, not v1 goals.
- D18 License: app and library code under PolyForm Noncommercial 1.0.0
  (source-available; noncommercial use free, commercial rights reserved to the
  author, so only the author may monetize). The format spec under CC BY 4.0, kept
  explicitly open so Engram files stay readable by any tool (no lock-in). Copyright
  is held under a project pseudonym, not a legal name. Deliberately not FLOSS, which
  precludes F-Droid (D17, roadmap).
- D19 Repo: conventional commits (feat:, fix:, docs:, ...), one line, no body;
  rebase over merge per owner's global git rules.
- D20 UI languages: Russian and English. Follows device locale and offers an
  in-app language switch (framework LocaleManager, per-app locale, declared in
  locales_config). Both string sets stay complete; further languages are additive.
- D21 Material and versions: Material 3 with dynamic color (Material You) as the
  default plus an optional mauve brand scheme (settings toggle), a shared
  EngramScaffold for standard top app bars, edge-to-edge, predictive-back opt-in,
  a themed monochrome icon and Android 12+ backup rules. Dependencies track newest
  stable; the Robolectric test SDK trails compileSdk (pinned to 36) until a release
  adds 37. CI adds CodeQL code scanning and dependency review. Deferred modernization
  (adaptive layouts, Material 3 Expressive, in-app predictive-back previews): roadmap.
- D22 Testing and coverage.

  Policy: integration/scenario tests are the primary, required way to cover new
  functionality; unit tests are a narrow supplement. Every change ships with tests,
  and coverage is measured, floored, and gated.

  Why integration-first: what we want confidence in is that real, user-facing flows
  work, so tests drive the real surface as close to end to end as possible rather than
  isolated units, and that integration coverage is what counts toward the number (the
  cli figure comes entirely from its integrationTest suite). Unit tests earn their
  place only when (a) the behavior is impractical to reach through an integration test
  and a unit test is materially faster or simpler (deep format-guard branches, awkward
  error injection), or (b) they guard a critical invariant we want to fail fast on when
  it is broken accidentally. They are a targeted supplement, never the default, and are
  not added for code an integration test already covers.

  Structure, two layers. (1) JVM + Robolectric, run under `./gradlew build`, measured
  by Kover, gated by the floors: core-format commonTest/jvmTest drive the codecs on
  real bytes (SyntheticMedia fixtures); cli integrationTest drives `cliMain` end to
  end; app test covers ViewModels, workers, and Compose screens rendered against a
  fake container. (2) Instrumented androidTest on a Gradle Managed Device
  (instrumented.yml, every PR) exercises the real platform adapters Kover cannot
  measure on-device; it is a confidence net, not part of the percentage, and it has
  already caught bugs the JVM layer could not (a coroutines version skew that only
  surfaced in the packaged APK).

  Key decisions. Screens resolve their AppContainer from the LocalAppContainer
  CompositionLocal, so a test hands them `fakeContainer()` (in-memory Room +
  FakeContentAccess + rows seeded via seedItem/seedMemory) and asserts populated
  renders and interactions; that seam took app from 78% to ~92%. Device-only Compose
  (MediaPlayer playback, SpeechRecognizer dictation, SAF result callbacks) is marked
  `@DeviceOnly` and excluded, along with the thin real platform adapters (MediaStore,
  SAF, MediaRecorder, Geocoder) and the debug LabScreen. The instrumented layer covers
  the app-owned MediaStore adapters and the SAF archive sink; MediaRecorder, Geocoder,
  Open-Meteo, SpeechRecognizer, and MediaPlayer playback stay manual device-QA
  (docs/device-qa.md), because they need real hardware, a network, or an OEM backend a
  headless CI emulator cannot provide deterministically. Counted coverage is therefore
  JVM + Robolectric only.

  Enforcement. Per-module `koverVerify` floors (core-format/cli 97, app 90) run inside
  `check`; the root `koverVerifyAggregate` task defends the combined 95%; both run in
  CI. Achieved ~98/98/92 per module, aggregate ~95.4%. Floors only ever rise, and they
  sit below the achieved numbers on purpose: Compose async timing gives ~0.5% run-to-
  run coverage variance, so a floor must clear the low end of a run, not its average.

- D23 Dependency security posture. Shipped artifacts carry only their declared runtime
  graphs (app AndroidX/Compose/Room/coroutines, cli core-format + kotlinx, core-format
  pure Kotlin). Dependabot alerts attributed to settings.gradle.kts are Android Gradle
  Plugin build/plugin-classpath transitives (netty, bouncycastle, logback, jose4j,
  jdom2, opentelemetry): build-time only, never shipped, and their vulnerable paths
  (GOST cipher, JWE parse, XXE, netty TLS/HTTP2 to attacker endpoints) are not invoked
  by a build that talks only to trusted repos over TLS. Not fixable via Dependabot (no
  manifest to bump), and force-pinning AGP transitives is fragile for no user benefit.
  Triage: dismiss as tolerable_risk with a comment; do not add buildscript pins or
  chase AGP for them. To keep them out of the graph in the first place, GitHub's
  Automatic Dependency Submission is disabled and .github/workflows/dependency-submission.yml
  submits only the shipped runtime configurations (app releaseRuntimeClasspath, cli
  runtimeClasspath), so build-classpath libraries are no longer reported.

- D24 Release pipeline (satisfies D17). Releases are tag-driven: pushing `vX.Y.Z` runs
  `.github/workflows/release.yml`, which builds and signs a single universal APK (pure
  JVM/ART bytecode, no ABI splits; no AAB until Play, deferred) and publishes it to
  GitHub Releases. Signing keys live only in CI secrets (ENGRAM_KEYSTORE_BASE64,
  ENGRAM_KEYSTORE_PASSWORD, ENGRAM_KEY_ALIAS, ENGRAM_KEY_PASSWORD); app/build.gradle.kts
  reads them from env or a git-ignored keystore.properties and stays unsigned when
  absent, so contributor builds and PR CI are unaffected. versionName comes from the tag
  and versionCode is major*1000000 + minor*1000 + patch (monotonic, Play-compatible),
  injected via -P. The asset is a stable `engram.apk` (so the landing button can use
  releases/latest/download/engram.apk) plus a SHA-256 and an SLSA build-provenance
  attestation (gh attestation verify). A hyphen tag (vX.Y.Z-rc1) publishes a prerelease.
  Release notes come from the matching CHANGELOG section. Before publishing, the workflow
  boots the signed APK on an emulator and fails the release if it crashes on launch (a
  release-artifact smoke gate; manual on-device QA is docs/device-qa.md). R8/shrink is
  off until a release-build device-test path exists.
- D25 Location preservation. Annotating a camera photo must not strip its GPS. The
  app holds ACCESS_MEDIA_LOCATION and reads unredacted originals via
  MediaStore.setRequireOriginal (finding 1); without the permission MediaStore
  redacts location out of the returned bytes, and the backup-and-rebuild write would
  then persist that loss. The permission rides the media-permission request and is
  optional: reads funnel through ResolverContentAccess.readUri (writes never do), and
  on denial the save warns that annotating will remove location, then proceeds. It
  adds no new egress; coordinates still leave the device only via the D10 enrichment
  toggle.
- D26 Never lose the photo. Media the app does not own has no atomic replace (opening
  with "wt" truncates in place), so write-back is transactional against the local
  backup. Content writes return a WriteResult tri-state, not a Boolean: NotOpened
  (target byte-for-byte untouched, no restore), OpenedUncertain (opened then failed,
  so the target may be truncated and is restored from backup), Ok (verify by
  re-parse). The backup is durable and ordered: the .meta sidecar is written
  fsync+rename first, then the .bak is published via tmp+fsync+rename and is never
  overwritten once committed; it is deleted only after the new file verifies intact
  or the original is restored. A per-instance Mutex serializes write-back and recovery
  so a foreground save and background recovery never interleave on the same file
  (finding 2).
- D27 Frame envelope frozen across wire versions. Spec sec 10 now freezes the frame
  field layout, so a v0 reader can locate every field of any future version.
  Records.decodeAt no longer rejects frames whose version byte is not 1: it decodes
  the envelope, computes the CRC, and surfaces the frame opaque (record null, version
  reported) exactly like an unknown kind. Rewriters and the strip-repair carry path
  already preserve opaque frames, so future-version records survive re-embeds instead
  of being invisible and truncating the stream (decodeSequence used to stop at the
  first unknown-version frame, hiding every record after it). Not a wire change:
  writers still emit version 1, no spec version bump. Carve safety: RecordStream.scan
  advances a full frame only for CRC-valid or current-version candidates; a CRC-bad
  unknown-version candidate advances one byte so a real frame inside its claimed span
  is still found. decodeAt also does its length checks in Long so a hostile payload
  length cannot wrap the bounds and crash the reader.
- D28 Archive record log. The Engram Archive is a commitment, not a convenience: spec
  sec 11 now defines it. The JSON view alone lost record order, ids, writers,
  timestamps, enrichment history, and every opaque frame, so calling it
  disaster-recovery overstated it. Each item now also writes a byte-exact
  `<hash>.records` sidecar (the CRC-valid frames concatenated in log order, wire
  format of spec sec 2) which the ordinary frame decoder reads back losslessly,
  opaque frames included; the manifest (v2) inventories every file with its md5 so
  completeness is checkable. The JSON stays as the human-readable view. The app
  exports from the strip-recovery cache (superset semantics, D3); the cli exports
  from the file. Hash identity stays md5 for naming and dedup, not security.

## 5. Assumptions register

- A1 All target devices run Android 13+. Verify by collecting the
  device list before first install; raising minSdk later is trivial, lowering
  is not.
- A2 Digest defaults (evening on, burst off) are right for this audience. Verify
  by usage after v1 lands; both are settings.
- A3 Weather and geocoding providers are selectable in implementation phase;
  must be free-tier friendly; every enrichment record stores provenance (source,
  fetch time). Enrichment calls leak GPS to the provider: pick accordingly,
  cache aggressively, keep the network toggle (D10).
- A4 RU + EN strings cover the whole audience.
- A5 License decision finalized at spec publication, not before.
- A6 Screenshots bucket is detectable via MediaStore relative path on the
  audience's OEMs. Verify on target devices [P0].
- A7 Voice notes are mono speech; Opus at roughly 24 to 32 kbps is sufficient.
- A8 Pixel screenshots are PNG; some OEMs use JPEG. Both bindings exist in v1
  (see 6). Verify actual formats on target devices [P0].

## 6. Format overview (the Engram format)

Logical model, container-agnostic:

- A media item carries an append-only log of records. Record kinds in v1:
  note (text, versioned), audio (MIME-tagged blob, optional transcript record
  linked to it), enrichment (place, weather, calendar ref, provenance-tagged).
- Every record has: id, kind, schema version, timestamp, writer id, CRC32.
  Binary records are self-delimiting (magic EGRM, length, CRC), so a carving
  tool can recover them even if the XMP index is lost or the file is truncated.
- Reading view: current memory = latest note version + all audio + latest
  enrichment. Full history remains accessible.
- Dual-write rule (D9): on every write session, latest note text is mirrored to
  the standard caption fields.

Container bindings:

- JPEG: structured data in XMP APP1, split into ExtendedXMP segments when
  large (implemented, md5 guid per Adobe spec); binary records appended after
  EOI. MPF safety (Ultra HDR gain maps are the default Pixel output): all
  insertions stay before the MPF APP2 so stored relative offsets keep meaning,
  every write is re-validated by MpfInspector, and layouts that would require
  offset rewriting are refused rather than risked. Motion Photos: the writer
  refuses to touch them until coexistence rules are device-verified [P0,
  landmine 2]; unparseable existing XMP aborts the write (fail closed).
- PNG (screenshots): XMP in the standard iTXt chunk; binary records in private
  ancillary safe-to-copy chunks.
- MP4/MOV: one custom uuid box (fixed UUID 7a0b5c4d-9e2f-4a31-8b6d-0c3e5f719246)
  holding all Engram records, appended at the tail. Caption mirror rewrites
  moov/udta/meta/ilst only when moov is the trailing box; moov-first layouts
  are declined because growing moov would break stco/co64 chunk offsets.
- HEIF/HEIC: deferred (roadmap); record model must not assume a JPEG-like tail.

Engram Archive (export format, also v1 feature per D14):

- A directory or zip at a user-chosen location: top-level manifest, one JSON
  document per media item keyed by content hash and original filename, audio
  blobs as plain files next to it. Schema documented in the spec. Doubles as
  the serialization of the recovery cache and as the disaster-recovery story.

Size: soft cap per D13. Integrity and versioning rules identical across bindings.

## 7. Product specification (v1 user journeys)

1. Onboarding: media permissions, notification permission, digest hour, one
   backup-honesty screen (what survives where, in one picture), language.
   Calendar access is an optional toggle, off until asked. Location access
   (ACCESS_MEDIA_LOCATION) rides the media-permission request so annotating reads the
   original bytes and never strips the photo's GPS; enrichment reuses that EXIF GPS
   (finding 1). Denial is allowed: the save warns that annotating will remove location.
2. Digest: one evening notification when un-annotated items exist. Opens the
   queue: full-screen item, hold-to-record voice, type text, or skip. Batch
   write consent once per session. Target under 15 seconds per item.
3. Burst nudge (off by default): gentle notification minutes after a shooting
   burst, same queue.
4. Browse: timeline of media with memories, filter by has/lacks memory, play
   voice notes, read notes with history, edit creates a new version.
5. Backfill: any old photo or video can be annotated the same way from browse.
6. Search: free text across notes and transcripts.
7. Strip detection: rescan notices a file whose records vanished but whose
   content hash matches the recovery cache; badge plus one-tap re-embed.
8. Export and verify: Engram Archive export to chosen location; backup verifier
   takes one file that round-tripped a cloud or messenger and reports what
   survived, in plain words.
9. Settings: sources (screenshots toggle), digest modes and hour, enrichment
   network toggle, remote-dictation consent toggle, calendar toggle, codec
   (advanced), language.

## 8. Architecture overview

- :core-format. Pure Kotlin, no android.*; KMP-compilable with an iosArm64 CI
  tripwire (sec 10). Owns parsing, writing, records, integrity, archive schema.
  Golden-file tests are the contract; the format engine is developed test-first.
- :cli. JVM wrapper over :core-format. Phase 0 lab tool, later the reference
  extractor shipped alongside the spec.
- :app. Compose UI, MediaStore integration, jobs, recorder, transcription,
  playback, index.

Dataflow: detect (content-trigger job + daily reconcile) -> queue (index) ->
annotate -> transactional write-back (durable backup, write, verify by re-parse,
restore from backup on an uncertain write; D26) -> index and recovery-cache update
-> async enrichment
worker, whose results are written together with the next user-initiated write
session where possible, avoiding rewrite churn on files.

Index: local DB, fully rebuildable from files (a user-visible maintenance
action); recovery cache is the sole non-rebuildable table (D3). No persistent
foreground services; everything is scheduled or user-initiated.

## 9. Android integration (component level)

- Permissions: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION (read original
  bytes so annotating preserves photo GPS), POST_NOTIFICATIONS, RECORD_AUDIO on first
  recording, READ_CALENDAR only behind its toggle.
- Writes: MediaStore.createWriteRequest, batched per annotate session; the
  transactional pattern above because no atomic replace exists for media the
  app does not own.
- Detection: JobScheduler addTriggerContentUri on MediaStore URIs plus periodic
  reconcile; buckets per D11.
- Transcription: on-device SpeechRecognizer preferred, network recognizer only after
  consent (finding 6); ru-RU primary [P0].
- Playback: Ogg Opus and AAC via platform players.
- Distribution: signed APK on GitHub Releases (D17).

## 10. iOS portability guardrails (constraints, not a build)

iOS is pushed far back (roadmap) but stays cheap to keep possible:

- :core-format compiles for iosArm64 in CI even with no consumer.
- Write strategy is pluggable: in-place rewrite (Android) vs copy-on-annotate
  or PhotoKit edit flows (iOS). The schema never assumes in-place.
- Media addressed by opaque handles, never file paths, in domain logic.
- No binding may be load-bearing for the record model (PNG and MP4 already
  force this discipline in v1).

## 11. Privacy model

- All user content stays on device or in files the user controls. No telemetry.
- Cloud backup excludes the record cache, voice drafts and write-back backups, so no
  memory content leaves the phone via Android Auto Backup (finding 5).
- Embedded context travels with shared files: share flows inside the app
  disclose what rides along; onboarding teaches once that outside-the-app
  sharing is not intercepted.
- Enrichment network calls expose GPS to the chosen provider (A3): toggleable,
  cached, provenance recorded.
- Engram Archive export is plaintext by design at a user-chosen location;
  encrypted export is on the roadmap; the app says this plainly at export time.
- Voice recordings are personal data: on-device processing preferred; the network
  recognizer is used only after an explicit consent, and audio is always kept locally.

## 12. Backup guidance (user-facing)

Every claim in this section is UNVERIFIED until the Phase 0 matrix rows land
(especially rows 2 to 4 and 10); nothing here ships as product guidance before
that.

- Expected recommendation: Google Photos Original quality, or Syncthing or NAS,
  or both (pending matrix rows 2 and 2b).
- In-app warning: Storage saver mode and most messengers destroy embedded
  memories (expected; final wording after the Phase 0 matrix).
- The backup verifier (D14) exists so this stops being theory: verify one file
  from your own backup path and see the survival report.

## 13. Scope

v1 checklist: digest + queue + fast annotate (text, voice, async enrichment),
transactional write-back for JPEG, PNG, MP4, browse and playback, backfill,
search, strip detection and re-embed, Engram Archive export, backup verifier,
both nudge modes, RU and EN UI, internal-testing distribution.

Cut from v1 (live in docs/roadmap.md): widget, bake-out share renders, folder
allowlist, HEIC, transcripts if the Russian gate fails, encrypted export,
memory sessions, shared pool, iOS, spec publication, production release.

## 14. Top risks

1. Behavioral: annotation cost is daily, value arrives years later. Mitigation:
   sub-15s flow, digest ritual, an engaged install base. Still the most likely
   failure mode.
2. Google Photos duplicate-on-rewrite of backed-up files [P0]. Could make the
   core loop feel broken; fallback is annotate-before-backup guidance or a
   Google Photos aware mode.
3. Silent format breakage by editors and OS updates. Mitigation: CRC scannable
   records, recovery cache, verifier, matrix re-runs per major OS release.
4. Russian on-device transcription quality [P0]. Fallback: audio-only v1.
5. Scope creep from three bindings (JPEG, PNG, MP4) in v1. Bounded: PNG chunks
   and MP4 boxes are far simpler than JPEG's segment surgery; JPEG carries the
   real risk (MPF, Motion).
6. Bus factor for a personal archive: mitigated by dual-write (D9), plaintext
   Archive export (D14), the spec, and the CLI extractor.

## 15. Phase 0 gates feeding this document

Survivability matrix (appendix A), Google Photos rewrite behavior, Ultra HDR
and Motion Photo coexistence, Russian transcription verdict, engram.cam
registration and namespace freeze, target device list (A1, A6, A8).

## Appendices

- A. Survivability matrix: produced by Phase 0, committed as ground truth.
- B. Format spec v0: spec/ directory, open under CC BY 4.0 (D18).
- C. Ambient AI constraints research: platform limits, consent law, market
  evidence, and the explicit conditions under which the topic reopens.
