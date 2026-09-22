using System.Text.Json;
using Detour.Domain.Pois;
using JV.ResultUtilities;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api;

/// <summary>
/// `dotnet Detour.Api.dll import-pois <path.json>` — loads an importer JSON file
/// (`tools/poi-importer/osm_import.py`'s output shape) and upserts it via
/// <see cref="IPoiRepository.UpsertAsync"/>. A CLI arg rather than an HTTP endpoint, same
/// reasoning as <see cref="RoadImport"/>: a one-shot admin action run by hand on the box that
/// hosts the database.
///
/// No missing-POI cleanup step — see <see cref="IPoiRepository.UpsertAsync"/>'s doc for why a
/// per-region run cannot safely delete what it didn't see.
/// </summary>
public static class PoiImport
{
    public const string Command = "import-pois";

    public static async Task RunAsync(string jsonPath, IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var repo = scope.ServiceProvider.GetRequiredService<IPoiRepository>();

        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath));
        var imported = 0;
        var skipped = 0;
        var index = 0;

        foreach (var el in doc.RootElement.GetProperty("pois").EnumerateArray())
        {
            var entryIndex = index++;

            // Same split as RoadImport: only parsing + entity construction is caught here, so
            // one malformed entry is counted and skipped rather than aborting a run of
            // thousands. UpsertAsync stays outside the try for the same reason — a database
            // failure must propagate, not read as "the JSON was bad".
            Result<Poi> result;
            try
            {
                var sourceId = el.GetProperty("sourceId").GetString()!;
                var kind = el.GetProperty("kind").GetString()!;
                var name = el.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                var lat = el.GetProperty("lat").GetDouble();
                var lon = el.GetProperty("lon").GetDouble();

                result = Poi.Create(sourceId, kind, name, lat, lon);
            }
            catch (Exception ex)
            {
                skipped++;
                var badSourceId = el.TryGetProperty("sourceId", out var sid) && sid.ValueKind == JsonValueKind.String
                    ? sid.GetString()
                    : "<unreadable>";
                Console.Error.WriteLine($"skipping pois[{entryIndex}] (sourceId={badSourceId}): {ex.Message}");
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
