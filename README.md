# Engram

Notes and voice, written into the photo itself.

Engram embeds text notes and short voice clips into media files (JPEG, PNG, MP4)
as versioned, CRC-protected records plus standard XMP captions. Memories travel
with the file: no accounts, no cloud, no lock-in. An Android app is planned; the
format engine and lab CLI live here today.

Honest limit, stated up front: embedded metadata survives file-copy paths
(backups, drives, original-quality cloud) and dies on recompressing share paths
(most messengers). Full reasoning: [docs/design.md](docs/design.md).

Status: Phase 0, format verification lab. Current work: [docs/plan.md](docs/plan.md).

## Build and verify

One command runs compilation, unit tests, integration tests, ktlint and detekt:

    ./gradlew build

Other useful entry points:

    ./gradlew ktlintFormat                 # autofix formatting
    ./gradlew :cli:run --args="selftest"   # end-to-end selfcheck
    ./gradlew :cli:run --args="inspect --in <file>"

Requires JDK 21 (or use the dev container in .devcontainer/).

## Documentation

The docs map with roles and update rules: [docs/README.md](docs/README.md).
Key entries: [design](docs/design.md), [current plan](docs/plan.md),
[roadmap](docs/roadmap.md), [changelog](CHANGELOG.md), agent rules in
[AGENTS.md](AGENTS.md).
