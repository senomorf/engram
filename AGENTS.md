# AGENTS.md

Engram: embeds memories (text, voice) into media files. Before non-trivial work
read docs/design.md (decisions D*, assumptions A*) for state, motivation and
rationale. Docs map and update rules: docs/README.md.

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

- SDK at ~/Android/Sdk; local.properties points to it. Set ANDROID_HOME for CLI gradle runs.
- Emulator headless via `sg kvm -c "<emulator cmd>"` (owner in kvm group; login shell may lack it). AVD `engram`, API 36 image; a targetSdk 37 build runs on it.
- compileSdk/targetSdk 37 (platform android-37.0). Robolectric 4.16.1 caps at API 36, so app/src/test/resources/robolectric.properties pins sdk=36; raise when Robolectric adds 37.
- AGP 9 built-in Kotlin: no standalone kotlin-android plugin in :app; compose-compiler plugin still required.
- Room suspend DAOs run off the test scheduler: use runBlocking + real settle in ViewModel tests, not advanceUntilIdle.
- Compose PascalCase functions are exempt from ktlint/detekt naming via config; do not rename them.
- git remote is HTTPS with gh as credential helper (no ssh-agent needed); push and fetch use the gh token.
- CI beyond `./gradlew build`: codeql (manual build mode, needs `--rerun-tasks` or Kotlin does not extract), dependency-review (PRs), pages (site/).

## Rules

- Conventional commits, one line, no body. Rebase over merge (D19).
- No em or en dashes anywhere (code, comments, docs, commits). Use commas, colons, hyphens.
- :core-format commonMain stays pure Kotlin: no java.* or android.* imports (iOS tripwire, design sec 10).
- Every JPEG write must leave MpfInspector valid; never bypass the post-write check (Ultra HDR survival).
- XMP namespace https://ns.engram.cam/1.0/ is frozen; wire-format changes need a spec version bump and a design decision.
- Records are append-only; nothing may silently drop or rewrite existing payload.
- Escape NUL as `\u0000` in source, never literal control bytes.
- lab/corpus/ holds private family media: never commit contents, never weaken its .gitignore.
- Licensing (D18): code under PolyForm Noncommercial 1.0.0 (root LICENSE); spec/ under CC BY 4.0 (spec/LICENSE). New code vs spec files go under the right one.
- Bug fix flow: reproduce with a failing test in core-format first.
- Linter split: ktlint owns formatting (.editorconfig), detekt owns smells (config/detekt/detekt.yml). No overlapping rules.
- Localization: user-facing strings via stringResource; keep values/ and values-ru/ in sync (only translatable=false and app_name differ), enforced by LocalizationTest and lint MissingTranslation. Lab debug diagnostics exempt.
- Offline: annotate, browse, search, verify, export must work with no network. Only enrichment uses the network, best-effort and graceful.
- Material: M3 only (no androidx.compose.material.* components), dynamic color, top bars via EngramScaffold, edge-to-edge; no hardcoded Color/TextStyle outside the theme. MaterialExpressiveTheme is internal in stable material3 1.4.0 (public only in 1.5.0-alpha); do not attempt until 1.5.0 stable (roadmap).

## Docs upkeep

- Which doc to update when, plus the living-state rules: docs/README.md (authoritative).
- Keep AGENTS.md current in the same change as the pattern, gotcha, command or rule it reflects.
