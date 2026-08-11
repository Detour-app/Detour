# Detour backend

The .NET replacement for `server/sync/sync_server.py`. One service, one database,
one identity provider.

- **What it must do:** [docs/rewrite/BACKEND_FUNCTIONAL_SPEC.md](../docs/rewrite/BACKEND_FUNCTIONAL_SPEC.md)
  — behaviour only, no code, deliberately language-agnostic.
- **Poking at it by hand:** [bruno/README.md](../bruno/README.md) — a generated
  Bruno collection covering every endpoint.

## Running it

The stack it talks to lives in [`docker/dev`](../docker/dev/README.md).

```bash
docker compose -f docker/dev/docker-compose.yml up -d
```

Then, from `backend/Detour/Detour.Api`:

```bash
dotnet run
```

It comes up on <http://localhost:7500>, applies its own migrations, and answers
`/api/health` with a per-dependency breakdown. OpenAPI is at `/openapi/v1.json`
in development only.

## Building and testing

One solution, so there is nothing to pick.

```bash
dotnet build backend/Detour.slnx
```

```bash
dotnet test backend/Detour.slnx --configuration Release
```

`Detour.Domain.Tests` is plain xUnit and runs in milliseconds.
`Detour.InfraTests` starts a real Postgres via Testcontainers — the InMemory
provider cannot reproduce citext comparison, jsonb columns, snake_case naming or
a unique-index violation, which is most of what those tests are for.

Style, which CI enforces:

```bash
dotnet format style backend/Detour.slnx --severity info --verify-no-changes
```

## Layout

```
backend/
  Detour.slnx
  Directory.Build.props        every project's TargetFramework, nullable, xunit config
  Directory.Packages.props     every package version, in one place
  Detour/
    Detour.Api                 controllers, DI, the middleware pipeline
    Detour.Domain              entities, repository interfaces, the rules
    Detour.Database            DbContext, entity configurations, repositories, migrations
    Detour.Domain.Tests        the rules, in isolation
    Detour.InfraTests          the API and the schema, against real Postgres
  Shared/                      cross-cutting libraries, nothing Detour-specific
```

`Domain` never references `Database`. It declares the repository interfaces;
`Database` implements them. `Api` is the only project that knows about ASP.NET
Core.

## Conventions worth knowing before you edit

- **Failures are `Result`, not exceptions.** Domain methods return
  `Result`/`Result<T>` carrying a `ValidationKeys` entry; controllers call
  `ThrowIfFailure()` and the global handler renders a localised 400. Adding an
  error path means adding a key *and* its English string in
  `Detour.Api/Translations/Translations.en.resx`.
- **Column names are never written down.** snake_case is applied globally by
  convention, so an explicit `HasColumnName` is almost always a mistake. The
  exception is owned-type flattening, where a prefix is needed to avoid a
  collision.
- **Enums are `SmartEnum`, stored by name.** Reordering members must never
  silently remap existing rows.
- **Entities enforce their own invariants.** Private constructor, static
  `Create` returning `Result<T>`, one validation method shared between create and
  update, no public setters.
- **Caps live in `DetourLimits`.** They are behaviour, not tuning: each exists
  because without it one client can grow another's data without bound.
- **The middleware order in `Startup.Configure` is a security boundary.** Each
  step carries a comment saying what breaks if it moves.

## What is deliberately absent

- **The convoy live surface.** Live position relay, push-to-talk and destination
  voting are not implemented — see §11 of the inventory doc for why the transport
  is still undecided. Circles are unaffected: their positions and presence events
  are ordinary REST reads and writes.
- **Background jobs.** Every retention cap is enforced at write time, where the
  row that would exceed it is created, so there is nothing for a sweep to do yet.
- **An audit trail.** `Shared.Audit` was left out of the port; it is the obvious
  next security addition.
- **Push notifications.** Absent in the server this replaces, and a new decision
  rather than a port.
