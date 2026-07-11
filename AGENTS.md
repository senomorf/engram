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
- coverage gates + reports: `koverVerify` (per-module floors) runs inside `check`, `./gradlew koverVerifyAggregate` enforces the combined 95% (both in CI); HTML via `:core-format:koverHtmlReport`, `:cli:koverHtmlReport`, `:app:koverHtmlReportDebug`
- e2e selfcheck: `./gradlew :cli:run --args="selftest"`
- survivability check: `engram verify --in <file> [--expect <sidecar>] --json` (exit 0/3/4 = intact/degraded/damaged)
- optional pre-commit hook: `git config core.hooksPath .githooks` (ktlintFormat + detekt on staged Kotlin before commit)

## Releasing

- Tag-driven signed APK (D24). To cut a release: promote CHANGELOG `[Unreleased]` to `## [X.Y.Z] - <date>`, commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`. `.github/workflows/release.yml` builds, signs, and publishes the APK + SHA-256 + SLSA provenance to GitHub Releases. Trial first with `vX.Y.Z-rc1` (hyphen = prerelease).
- versionName/Code come from the tag (versionCode = major*1000000+minor*1000+patch); never hand-bump the literals in app/build.gradle.kts. Signing secrets (owner-provisioned, never committed): `ENGRAM_KEYSTORE_BASE64`, `ENGRAM_KEYSTORE_PASSWORD`, `ENGRAM_KEY_ALIAS`, `ENGRAM_KEY_PASSWORD`. Local signed build: copy `keystore.properties.example` to `keystore.properties` (git-ignored).
- Release gate (Track A v1 exit criteria, docs/plan.md): do not promote a non-rc tag until the survivability-matrix rows 1 to 11 and the landmine verdicts are recorded in lab/survivability-matrix.md and the docs/device-qa.md pass is done. rc tags (hyphen suffix) are exempt so the pipeline can still be trialed.

## Environment gotchas

- SDK at ~/Android/Sdk; local.properties points to it. Set ANDROID_HOME for CLI gradle runs.
- Emulator headless via `sg kvm -c "<emulator cmd>"` (owner in kvm group; login shell may lack it). AVD `engram`, API 36 image; a targetSdk 37 build runs on it.
- compileSdk/targetSdk 37 (platform android-37.0). Robolectric 4.16.1 caps at API 36, so app/src/test/resources/robolectric.properties pins sdk=36; raise when Robolectric adds 37.
- AGP 9 built-in Kotlin: no standalone kotlin-android plugin in :app; compose-compiler plugin still required.
- Room suspend DAOs run off the test scheduler: use runBlocking + real settle in ViewModel tests, not advanceUntilIdle.
- Compose PascalCase functions are exempt from ktlint/detekt naming via config; do not rename them.
- git remote is HTTPS with gh as credential helper (no ssh-agent needed); push and fetch use the gh token.
- CI beyond `./gradlew build`: codeql (manual build mode, needs `--rerun-tasks` or Kotlin does not extract), dependency-review (PRs), pages (site/).
- claude-code-review.yml: the code-review plugin posts to PRs only when the prompt has --comment (stock scaffold omits it, anthropics/claude-code-action#1087); keep the flag. PRs that edit this workflow file are skipped by OIDC validation (green run, warning in log) until merged to main.

## Rules

- Conventional commits, one line, no body. Rebase over merge (D19).
- No em or en dashes anywhere (code, comments, docs, commits). Use commas, colons, hyphens.
- :core-format commonMain stays pure Kotlin: no java.* or android.* imports (iOS tripwire, design sec 10).
- Every JPEG write must leave MpfInspector valid; never bypass the post-write check (Ultra HDR survival). Any insertion that grows the primary must patch the MPF primary MP-entry Individual Image Size to the new SOI..EOI span (MpfInspector now enforces it).
- XMP namespace https://ns.engram.cam/1.0/ is frozen; wire-format changes need a spec version bump and a design decision.
- Records are append-only; nothing may silently drop or rewrite existing payload. The IPTC caption upsert likewise preserves other IIM datasets (keywords, by-line, copyright); only 2:120 is replaced.
- Reads of user media go through ResolverContentAccess.readUri (MediaStore.setRequireOriginal, gated on ACCESS_MEDIA_LOCATION); a new reader that opens the URI directly strips a camera photo's GPS (design.md D25).
- Content writes are a WriteResult tri-state (NotOpened/OpenedUncertain/Ok), not Boolean; durable .meta-before-.bak backup, the .bak is deleted only after the new file verifies intact (every expected record id present) or the original is restored, a new attempt resolves any pending journal first and bails without touching it when it cannot, a Mutex serializes write and recovery (design.md D26).
- Escape NUL as `\u0000` in source, never literal control bytes.
- lab/corpus/ holds private personal media: never commit contents, never weaken its .gitignore.
- Licensing (D18): code under PolyForm Noncommercial 1.0.0 (root LICENSE); spec/ under CC BY 4.0 (spec/LICENSE). New code vs spec files go under the right one.
- Bug fix flow: reproduce with a failing test in core-format first.
- Testing (D22): integration/scenario tests are the primary, required way to cover new functionality: drive the real surface end to end (cli via `cliMain`, screens via `fakeContainer()` + `setScreen` with `seedItem`/`seedMemory`, codecs on real bytes via `SyntheticMedia`). Every feature or fix ships integration coverage. Unit tests are a narrow supplement, only when (a) a behavior is impractical to reach via integration and a unit test is materially faster/simpler (deep guard branches, awkward error paths), or (b) it guards a critical invariant we want to fail fast on. Never add broad unit suites for code integration tests already cover. Structure, patterns, rationale: design.md D22.
- Coverage (D22): per-module `koverVerify` floors (core-format/cli 97, app 90) run in `check`; root `koverVerifyAggregate` defends the combined 95% (both enforced in CI). Floors only rise, kept below achieved ~98/98/92 to absorb ~0.5% Compose-timing variance. Counted coverage is JVM/Robolectric only (Kover cannot measure on-device); real platform adapters (MediaStore, SAF, SpeechRecognizer, MediaRecorder, Geocoder), LabScreen, and device-only Compose marked `@DeviceOnly` are excluded and covered by the instrumented androidTest layer.
- Dependency security (D23): Dependabot alerts on the AGP build classpath (settings.gradle.kts, scope null: netty/bouncycastle/logback/jose4j/jdom2/opentelemetry) are build-time only, not shipped; dismiss as tolerable_risk, never add buildscript pins. Graph submission (dependency-submission.yml) is scoped to shipped runtime configs and the repo's Automatic Dependency Submission is OFF, so the build classpath is no longer graphed. Rationale: design.md D23. Gotcha: `gh api .../dependabot/alerts` paginates at 30, use `--paginate` to see all.
- Linter split: ktlint owns formatting (.editorconfig), detekt owns smells (config/detekt/detekt.yml). No overlapping rules.
- Localization: user-facing strings via stringResource; keep values/ and values-ru/ in sync (only translatable=false and app_name differ), enforced by LocalizationTest and lint MissingTranslation. Lab debug diagnostics exempt.
- Offline: annotate, browse, search, verify, export must work with no network. Only enrichment and consented remote dictation use the network, best-effort and graceful.
- Material: M3 only (no androidx.compose.material.* components), dynamic color, top bars via EngramScaffold, edge-to-edge; no hardcoded Color/TextStyle outside the theme. MaterialExpressiveTheme is internal in stable material3 1.4.0 (public only in 1.5.0-alpha); do not attempt until 1.5.0 stable (roadmap).

## Docs upkeep

- Which doc to update when, plus the living-state rules: docs/README.md (authoritative).
- Keep AGENTS.md current in the same change as the pattern, gotcha, command or rule it reflects.
