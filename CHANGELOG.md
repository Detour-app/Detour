# Changelog

User-facing release notes, one section per `versionName`. CI extracts the
section matching the version being published and sends it to Google Play as
that release's "what's new" text — see `.github/workflows/build.yml`.

Add a section here in the same PR that bumps `versionName` in
`app/build.gradle.kts` (see `CLAUDE.md` → Versioning). Keep entries short and
user-facing (what changed for the rider, not implementation detail); the
whole section is capped at 500 characters by Play, so lead with what matters.

## 1.76.0

- First tracked release under this changelog.
