using System.Text.Json;
using Detour.Domain.Boundaries;
using JV.ResultUtilities;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api;

/// <summary>
/// `dotnet Detour.Api.dll import-municipalities <path.json>` — loads an importer JSON file
/// (`tools/municipality-importer/osm_import.py`'s output shape) and upserts it via
/// <see cref="IMunicipalityBoundaryRepository.UpsertAsync"/>. A CLI arg rather than an HTTP
/// endpoint, same reasoning as <see cref="RoadImport"/>: a one-shot admin action run by hand on
/// the box that hosts the database.
///
/// No missing-boundary cleanup step — see
/// <see cref="IMunicipalityBoundaryRepository.UpsertAsync"/>'s doc for why a per-region run
/// cannot safely delete what it didn't see.
/// </summary>
public static class MunicipalityImport
{
    public const string Command = "import-municipalities";

    public static async Task RunAsync(string jsonPath, IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var repo = scope.ServiceProvider.GetRequiredService<IMunicipalityBoundaryRepository>();

        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath));
        var imported = 0;
        var skipped = 0;
        var index = 0;

        foreach (var el in doc.RootElement.GetProperty("boundaries").EnumerateArray())
        {
            var entryIndex = index++;

            // Same split as RoadImport/SpeedLimitImport: only parsing + entity construction is
            // caught here, so one malformed entry is counted and skipped rather than aborting a
            // run of thousands. UpsertAsync stays outside the try — a database failure must
            // propagate, not read as "the JSON was bad".
            Result<MunicipalityBoundary> result;
            try
            {
                var sourceId = el.GetProperty("sourceId").GetString()!;
                var name = el.GetProperty("name").GetString()!;
                var rings = el.GetProperty("rings").EnumerateArray()
                    .Select(ring => (IReadOnlyList<(double, double)>)
                        [.. ring.EnumerateArray().Select(p => (p[0].GetDouble(), p[1].GetDouble()))])
                    .ToList();

                result = MunicipalityBoundary.Create(sourceId, name, rings);
            }
            catch (Exception ex)
            {
                skipped++;
                var badSourceId = el.TryGetProperty("sourceId", out var sid) && sid.ValueKind == JsonValueKind.String
                    ? sid.GetString()
                    : "<unreadable>";
                Console.Error.WriteLine($"skipping boundaries[{entryIndex}] (sourceId={badSourceId}): {ex.Message}");
                continue;
            }

            if (result.IsFailure) { skipped++; continue; }

            await repo.UpsertAsync(result.Value, CancellationToken.None);
            imported++;
        }

        await repo.FlushChangesAsync(CancellationToken.None);

        Console.WriteLine($"{Path.GetFileName(jsonPath)}: upserted {imported}, skipped {skipped} invalid");
    }
}
