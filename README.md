# Engram

Notes and voice, written into the photo itself.

Engram embeds text notes and short voice clips into media files (JPEG, PNG, MP4)
as versioned, CRC-protected records plus standard XMP captions. Memories travel
with the file: no accounts, no cloud, no lock-in. This repo holds the pure-Kotlin
format engine, a reference CLI, and the Android app.

Honest limit, stated up front: embedded metadata survives file-copy paths
(backups, drives, original-quality cloud) and dies on recompressing share paths
(most messengers). Full reasoning: [docs/design.md](docs/design.md).

Status: Android app implemented and pre-release; format engine and CLI stable.
Current work: [docs/plan.md](docs/plan.md).

## Highlights

- Works offline. Capturing notes and voice, browsing, search, backup verification
  and archive export all run with no network. Only optional context enrichment
  (weather, place names) reaches out, and it degrades silently when offline.
- Fully bilingual: complete English and Russian, with an in-app language switch
  (and per-app locale). Voice dictation follows the chosen language. More
  languages are additive from here.
- Material 3 with Material You dynamic color, standard top app bars and
  edge-to-edge, targeting Android 16 (API 37) down to Android 13 (API 33).
- Private by design: no accounts, no telemetry, nothing leaves the device unless
  you share the file yourself.

## Build and verify

One command runs compilation, unit tests, integration tests, ktlint and detekt:

    ./gradlew build

Other useful entry points:

    ./gradlew ktlintFormat                 # autofix formatting
    ./gradlew :cli:run --args="selftest"   # end-to-end selfcheck
    ./gradlew :cli:run --args="inspect --in <file>"

Requires JDK 21 (or use the dev container in .devcontainer/).

## License

App and library code is under PolyForm Noncommercial 1.0.0 (see [LICENSE](LICENSE)):
noncommercial use is free, commercial rights are reserved. The Engram format
specification ([spec/](spec/)) is under CC BY 4.0 (see [spec/LICENSE](spec/LICENSE)),
kept open so your files stay readable by any tool.

## Documentation

The docs map with roles and update rules: [docs/README.md](docs/README.md).
Key entries: [design](docs/design.md), [current plan](docs/plan.md),
[roadmap](docs/roadmap.md), [changelog](CHANGELOG.md), agent rules in
[AGENTS.md](AGENTS.md).
