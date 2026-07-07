# AGENTS.md

Engram: embeds memories (text, voice) into media files. Before non-trivial work
read docs/design.md (decisions D1-D20, assumptions A1-A8). Docs map: docs/README.md.

## Commands

- verify everything: `./gradlew build` (compiles, unit + integration tests, ktlint, detekt)
- iOS portability tripwire: `./gradlew :core-format:compileKotlinIosArm64` (also in CI)
- autofix formatting: `./gradlew ktlintFormat` (run after editing Kotlin, before commit)
- e2e selfcheck: `./gradlew :cli:run --args="selftest"`
- inspect a media file: `./gradlew :cli:run --args="inspect --in <file>"`
- survivability check: `engram verify --in <file> [--expect <sidecar>] --json` (exit 0/3/4 = intact/degraded/damaged)

## Rules

- Conventional commits, one line, no body. Rebase over merge.
- No em or en dashes anywhere (code, comments, docs, commits). Use commas, colons, hyphens.
- :core-format commonMain stays pure Kotlin: no java.* or android.* imports (iOS portability tripwire, design doc sec 10).
- Every JPEG write must leave MpfInspector valid; never bypass the post-write check (Ultra HDR survival).
- XMP namespace https://ns.engram.photos/1.0/ is frozen. Record wire format changes require a spec version bump and a design doc decision.
- Records are append-only; nothing may silently drop or rewrite existing payload.
- Escape NUL as `\u0000` in source, never literal control bytes.
- lab/corpus/ holds private family media: never commit contents, never weaken its .gitignore.
- Bug fix flow: reproduce with a failing test in core-format first.
- Linter split: ktlint owns formatting (.editorconfig), detekt owns smells (config/detekt/detekt.yml). Do not add overlapping rules.

## Docs upkeep (details in docs/README.md)

- Decision made or changed: docs/design.md (decision log).
- Phase progress or scope shift: docs/plan.md. Future ideas: docs/roadmap.md.
- User-visible change: CHANGELOG.md under [Unreleased].
- New agent-relevant rule or command: this file.
- Docs are living state, not journals: update in place, keep short, link instead of duplicating.
