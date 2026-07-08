# AGENTS.md

Engram: embeds memories (text, voice) into media files. Before non-trivial work
read docs/design.md (decisions D1-D21, assumptions A1-A8) for state, motivation
and assumptions. Docs map: docs/README.md.

## Modules

- :core-format pure-Kotlin format engine (records, JPEG/PNG/MP4 bindings, XMP,
  Memory reading view, archive). :cli JVM reference tool. :app Android (Compose,
  Room, WorkManager, manual DI in AppContainer).

## Commands

- verify everything: `./gradlew build` (compiles, unit + integration tests, ktlint, detekt, AGP lint)
- iOS portability tripwire: `./gradlew :core-format:compileKotlinIosArm64` (also in CI)
- autofix formatting: `./gradlew ktlintFormat` (run after editing Kotlin, before commit)
- android debug apk + unit tests: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
- e2e selfcheck: `./gradlew :cli:run --args="selftest"`
- survivability check: `engram verify --in <file> [--expect <sidecar>] --json` (exit 0/3/4 = intact/degraded/damaged)

## Environment gotchas

- SDK bootstrapped at ~/Android/Sdk; local.properties points to it. Set ANDROID_HOME for CLI gradle runs.
- Emulator runs headless via `sg kvm -c "<emulator cmd>"` (owner is in the kvm group; a login shell may not have it yet). AVD `engram`, API 36 image; a targetSdk 37 build installs and runs on it fine.
- compileSdk/targetSdk 37 (platform android-37.0). Robolectric 4.16.1 caps at API 36, so app/src/test/resources/robolectric.properties pins sdk=36; raise it when a Robolectric release adds 37.
- AGP 9 built-in Kotlin: no standalone kotlin-android plugin in :app; compose-compiler plugin is still required.
- Room suspend DAOs run off the test scheduler: use runBlocking + real settle in ViewModel tests, not advanceUntilIdle.
- Compose PascalCase functions are exempted from ktlint/detekt naming via config; do not rename them.

## Rules

- Conventional commits, one line, no body. Rebase over merge.
- No em or en dashes anywhere (code, comments, docs, commits). Use commas, colons, hyphens.
- :core-format commonMain stays pure Kotlin: no java.* or android.* imports (iOS portability tripwire, design doc sec 10).
- Every JPEG write must leave MpfInspector valid; never bypass the post-write check (Ultra HDR survival).
- XMP namespace https://ns.engram.cam/1.0/ is frozen. Record wire format changes require a spec version bump and a design doc decision.
- Records are append-only; nothing may silently drop or rewrite existing payload.
- Escape NUL as `\u0000` in source, never literal control bytes.
- lab/corpus/ holds private family media: never commit contents, never weaken its .gitignore.
- Licensing (D18): code under PolyForm Noncommercial 1.0.0 (root LICENSE); spec/ under CC BY 4.0 (spec/LICENSE). Put new code vs spec files under the right one.
- Bug fix flow: reproduce with a failing test in core-format first.
- Linter split: ktlint owns formatting (.editorconfig), detekt owns smells (config/detekt/detekt.yml). Do not add overlapping rules.
- Localization: every user-facing string via stringResource; keep values/ and values-ru/ in sync (only translatable=false entries and app_name may differ), enforced by LocalizationTest and lint MissingTranslation. Lab debug diagnostics are exempt. Voice dictation language is decoupled from UI language (Dictation.supportedLanguages).
- Offline: annotate, browse, search, verify and export must work with no network. Only enrichment may use the network, and only best-effort and graceful.
- Material: M3 only (no androidx.compose.material.* components), dynamic color, top bars via EngramScaffold, edge-to-edge. Do not hardcode Color/TextStyle outside the theme. MaterialExpressiveTheme is internal in stable material3 1.4.0 (public only in 1.5.0-alpha); do not attempt it until 1.5.0 is stable (roadmap).

## Docs upkeep (details in docs/README.md)

- Decision made or changed: docs/design.md (decision log).
- Phase progress or scope shift: docs/plan.md. Future ideas: docs/roadmap.md.
- User-visible change: CHANGELOG.md under [Unreleased].
- New agent-relevant rule or command: this file.
- Docs are living state, not journals: update in place, keep short, link instead of duplicating.
- Keep this file current every session: when a pattern, gotcha, command or rule changes, update AGENTS.md in the same change, not later.
