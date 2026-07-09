# Contributing

Thanks for your interest in Engram. The project is developed in the open under a
noncommercial license (see [LICENSE](LICENSE) and design decision D18); the format spec
is separately CC BY 4.0 so Engram files stay readable by any tool.

## Before you start

- Read [AGENTS.md](AGENTS.md) and [docs/design.md](docs/design.md) (decisions D*,
  assumptions A*) for state and rationale. The docs map is [docs/README.md](docs/README.md).
- Check [docs/roadmap.md](docs/roadmap.md): some ideas are already planned or explicitly
  deferred (for example iOS, Play, F-Droid).

## Ground rules

- Tests are integration-first and required for new functionality (D22): drive the real
  surface end to end. Unit tests are a narrow supplement, not the default.
- Conventional commits, one line, no body. Rebase over merge (D19).
- No em or en dashes anywhere (code, comments, docs). Use commas, colons, hyphens.
- `:core-format` commonMain stays pure Kotlin (no `java.*`/`android.*`), for the iOS port.
- Keep AGENTS.md and docs current in the same change as the rule, command, or decision.

## Build and verify

- Everything: `./gradlew build` (compiles, unit + integration tests, ktlint, detekt, AGP
  lint, coverage gate).
- Autofix formatting after editing Kotlin: `./gradlew ktlintFormat`.
- Optional pre-commit hook (ktlintFormat + detekt on staged Kotlin): `git config core.hooksPath .githooks`.
- iOS portability tripwire: `./gradlew :core-format:compileKotlinIosArm64`.
- CLI selftest: `./gradlew :cli:run --args="selftest"`.
- More commands (emulator, coverage reports): AGENTS.md.

## Pull requests

- Branch off `main`, keep changes focused, and fill in the PR checklist.
- CI must be green (build, androidTest, CodeQL, dependency-review) before merge.
- Merges are rebase, not squash or merge commits (D19).

## Releases

Owner-only and tag-driven: see the Releasing section in AGENTS.md and the device QA
checklist in [docs/device-qa.md](docs/device-qa.md).
