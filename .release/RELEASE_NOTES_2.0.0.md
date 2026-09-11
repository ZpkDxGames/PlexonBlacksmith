# PlexonBlacksmith 2.0.0

Stable 2.0 promotes the accepted exact-item Blacksmith workstation and Phase 3 player UX to the reproducible stable release line.

## Included

- Repair, Combine and Enchant services with exact result previews and deterministic quotes.
- Phase 3 Blacksmith Home and explicit service routing without a second transaction engine.
- Exact custom-item custody and metadata preservation across supported operations.
- Vault/Theosis economy authority with bounded compensating refund behavior on pre-commit failure.
- Confirmation protection for donor/book consumption and expensive repairs.
- Core 1/2 lifecycle compatibility and standalone fallback.

## Stable source fix

`/blacksmith reload` now fails closed when the candidate `config.yml` cannot be parsed. A malformed or missing candidate is rejected before active workstation sessions are closed or runtime prices/features change. The current runtime and exact cached inputs remain authoritative.

Regression coverage proves that a rejected malformed reload retains the same active session ID and damaged cached input.

## Release verification

The stable publisher accepts only the exact current `main` commit, proves accepted Phase 2 / Phase 3 / RC2 ancestry, rebuilds and tests the exact source, verifies Java 25 distribution isolation, publishes the JAR plus checksum/test/provenance evidence, then downloads the public assets and verifies them before the release workflow can pass.

Live PlexonCraft runtime certification is not inferred from GitHub CI and is recorded as `runtime_certification=NOT_EXECUTED` until deployment validation is performed.

## Rollback

Authoritative stable rollback remains immutable `v1.4.1`:

- source: `2fca852d0c2e9efddd9d8d55865c7b5c0c46fcb5`
- JAR SHA-256: `0c371dc03be7fec01463c2024b98e3ad804f06a7af5d45c8be848cf5733ae88e`

Historical `v2.0.0-rc.2` remains immutable and is retained as accepted prerelease provenance.
