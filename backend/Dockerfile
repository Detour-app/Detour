# The build context is backend/, not the repository root: Detour.Api references
# projects across Shared/, and both live under here. The web planner (#913) is
# the one thing from outside it, passed as a second, named context `web`:
#
#   docker build --build-context web=web -t detour-api backend
#
# (from the repository root). Without `--build-context web=...` the build fails
# at the planner stage below: an image without the planner would be a silent
# regression for everyone who pulls it.

# The planner: a static Vite build, copied into the API image and served under
# /planner (Detour.Api/Planner/PlannerHosting.cs). On the build platform, not the
# target one: the output is plain files, the same for amd64 and arm64, so
# building it under QEMU for arm64 would only cost minutes. Named `planner`, not
# `web`: a stage with the build context's name would shadow it.
FROM --platform=$BUILDPLATFORM node:22-alpine AS planner
WORKDIR /web
# Manifest first, so a source-only change keeps the npm ci layer.
COPY --from=web package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=web . .
RUN npm run build

FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# The whole tree at once rather than a hand-listed set of .csproj files. That
# costs a restore on any source change, and it is the right trade here: CI has a
# drift guard asserting every .csproj is in the .slnx, and a COPY list would be a
# second inventory to keep in step with no guard behind it.
COPY . .

# Restore from the API project, not the solution — it pulls in exactly the
# projects the service needs and leaves the Testcontainers and xunit graph out.
RUN dotnet restore Detour/Detour.Api/Detour.Api.csproj

RUN dotnet publish Detour/Detour.Api/Detour.Api.csproj \
    --configuration Release \
    --no-restore \
    --output /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app ./
COPY --from=planner /web/dist ./planner
ENV Planner__Path=/app/planner

# The same port the service uses everywhere else, so a URL copied out of
# docker/dev/README.md keeps working against a container.
ENV ASPNETCORE_HTTP_PORTS=7500
EXPOSE 7500

# The Data Protection key ring (#802: it encrypts each rider's stored HERE key)
# lives here when DataProtection__KeysPath=/keys, as docker/prod sets it. Created
# and owned by the app user now, because a named volume mounted on an existing
# directory takes that directory's owner; mounted on a missing one it would be
# root's, and the non-root user below could not write the ring.
RUN mkdir /keys && chown $APP_UID /keys

# Non-root, using the user the runtime image already ships. Apart from /keys
# above, nothing here writes to disk: logs go to stdout and file logging is off
# unless Serilog:EnableFileLogging is turned on, which needs a volume and a
# writable path chosen deliberately.
USER $APP_UID

# No HEALTHCHECK on purpose: the runtime image carries no HTTP client, and adding
# a package so the container can curl itself is worse than letting whatever runs
# it probe /api/health, which answers with a per-dependency breakdown.
ENTRYPOINT ["dotnet", "Detour.Api.dll"]
