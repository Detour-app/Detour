using System.Text.Json;
using Detour.Domain.Roads;
using JV.ResultUtilities;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api;

/// <summary>
/// `dotnet Detour.Api.dll import-speedlimits <path.json>` — loads an importer JSON file
/// (`tools/speedlimit-importer/osm_import.py`'s output shape) and upserts it via
/// <see cref="ISpeedLimitWayRepository.UpsertBatchAsync"/>. A CLI arg rather than an HTTP endpoint,
/// same reasoning as <see cref="CameraImport"/>: a one-shot admin action run by hand on the box
/// that hosts the database.
///
/// No missing-way cleanup step — see <see cref="ISpeedLimitWayRepository.UpsertAsync"/>'s doc
/// for why a per-region run cannot safely delete what it didn't see.
/// </summary>
public static class SpeedLimitImport
{
    /// <summary>Rows per <see cref="ISpeedLimitWayRepository.UpsertBatchAsync"/> call: one lookup
    /// query and one flush each, and the change tracker never holds more than this many rows.</summary>
    private const int BatchSize = 1000;

    public const string Command = "import-speedlimits";

    public static async Task RunAsync(string jsonPath, IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var repo = scope.ServiceProvider.GetRequiredService<ISpeedLimitWayRepository>();

        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath));
        var valid = new List<SpeedLimitWay>();
        var skipped = 0;
        var index = 0;

        foreach (var el in doc.RootElement.GetProperty("ways").EnumerateArray())
        {
            var entryIndex = index++;

            // Same split as CameraImport: only parsing + entity construction is caught here, so
            // one malformed entry is counted and skipped rather than aborting a run of
            // thousands. The upsert stays outside the try for the same reason — a database
            // failure must propagate, not read as "the JSON was bad".
            Result<SpeedLimitWay> result;
            try
            {
                var sourceId = el.GetProperty("sourceId").GetString()!;
                var maxSpeed = el.GetProperty("maxSpeedKmh").GetInt32();
                var polyline = el.GetProperty("polyline").EnumerateArray()
                    .Select(p => (p[0].GetDouble(), p[1].GetDouble())).ToList();

                result = SpeedLimitWay.Create(sourceId, polyline, maxSpeed);
            }
            catch (Exception ex)
            {
                skipped++;
                var badSourceId = el.TryGetProperty("sourceId", out var sid) && sid.ValueKind == JsonValueKind.String
                    ? sid.GetString()
                    : "<unreadable>";
                Console.Error.WriteLine($"skipping ways[{entryIndex}] (sourceId={badSourceId}): {ex.Message}");
                continue;
            }

            if (result.IsFailure) { skipped++; continue; }

            valid.Add(result.Value);
        }

        // Each batch commits on its own, so a failure part-way leaves the earlier batches
        // written; rerunning the same file is safe because every write is an upsert.
        foreach (var batch in valid.Chunk(BatchSize))
            await repo.UpsertBatchAsync(batch, CancellationToken.None);

        Console.WriteLine($"{Path.GetFileName(jsonPath)}: upserted {valid.Count}, skipped {skipped} invalid");
    }
}
