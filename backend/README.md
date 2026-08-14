# Detour backend

The sync and social service. One service, one database,
one identity provider.

- **What it must do:** [docs/BACKEND_SPEC.md](../docs/BACKEND_SPEC.md) —
  behaviour and rules, no code. Backend comments cite its sections as
  `spec §11`, so its numbering is stable.
- **The group features in detail:**
  [docs/CIRCLES_AND_CONVOYS.md](../docs/CIRCLES_AND_CONVOYS.md) — convoys,
  circles, and the live relay's wire format.
- **Poking at it by hand:** [bruno/README.md](../bruno/README.md) — a generated
  Bruno collection covering every endpoint.
- **Standing it up somewhere real:** [INSTALL.md](INSTALL.md) — the container, the
  configuration that matters, and what is still missing.

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

There is a container too, for anywhere that is not a development machine:

```bash
docker build -t detour-api backend
```

The context is `backend/` rather than the repository root, because the API
references projects across `Shared/` and nothing outside `backend/` is needed to
build it. [INSTALL.md](INSTALL.md) covers what it needs around it.

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

- **Voice.** Push-to-talk frames are accepted off the live socket and dropped,
  the same as any unknown type, so a client that still sends them stays connected
  and everything else keeps working. What comes back will be Opus over binary
  frames: raw PCM base64'd into JSON cost about 40 KB/s per talker per listener,
  which is what made it worth deferring rather than porting.
- **Background jobs.** Every retention cap is enforced at write time, where the
  row that would exceed it is created, so there is nothing for a sweep to do yet.
- **An audit trail.** `Shared.Audit` was left out of the port; it is the obvious
  next security addition.
- **Push notifications.** Absent in the server this replaces, and a new decision
  rather than a port.
