# Security policy

## Reporting a vulnerability

Please report security issues privately, not as public issues. Use GitHub's private
vulnerability reporting (enabled on this repo): open the
[Security tab](https://github.com/senomorf/engram/security/advisories/new) and click
"Report a vulnerability". Expect an acknowledgement within a few days.

## Supported versions

Engram is pre-1.0 and ships as a signed APK on GitHub Releases (design D17). Security
fixes land on the latest release; there are no maintained older lines yet.

## What to expect

- The app is offline-first: annotate, browse, search, verify, and export work with no
  network; only best-effort enrichment (weather, geocoding) uses it. No accounts, no
  telemetry (D10).
- Release APKs are signed and carry a SHA-256 checksum and an SLSA build-provenance
  attestation. Verify a download with:
  `gh attestation verify engram.apk --repo senomorf/engram`.
- Dependency alerts attributed to the Android Gradle Plugin build classpath
  (`settings.gradle.kts`) are build-time only and never shipped in the APK or CLI; that
  triage is documented as design decision D23.
