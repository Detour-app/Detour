# The build context is backend/, not the repository root: Detour.Api references
# projects across Shared/, and both live under here. Nothing outside backend/ is
# needed to build the service.
#
#   docker build -t detour-api backend
#
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

# The same port the service uses everywhere else, so a URL copied out of
# docker/dev/README.md keeps working against a container.
ENV ASPNETCORE_HTTP_PORTS=7500
EXPOSE 7500

# Non-root, using the user the runtime image already ships. Nothing here writes to
# disk: logs go to stdout and file logging is off unless Serilog:EnableFileLogging
# is turned on, which needs a volume and a writable path chosen deliberately.
USER $APP_UID

# No HEALTHCHECK on purpose: the runtime image carries no HTTP client, and adding
# a package so the container can curl itself is worse than letting whatever runs
# it probe /api/health, which answers with a per-dependency breakdown.
ENTRYPOINT ["dotnet", "Detour.Api.dll"]
