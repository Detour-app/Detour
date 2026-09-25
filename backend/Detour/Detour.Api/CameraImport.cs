using System.Text.Json;
using Detour.Domain.Cameras;
using JV.ResultUtilities;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api;

/// <summary>
/// `dotnet Detour.Api.dll import-cameras <path.json>` — loads an importer JSON file (Task 5's
/// output shape) and upserts it via <see cref="ICameraRepository.UpsertAsync"/>. A CLI arg
/// rather than an HTTP endpoint: this is a one-shot admin action run by hand on the box that
/// hosts the database, and adding an authenticated import endpoint would be a second auth
/// surface to secure for a job nothing else ever triggers.
/// </summary>
public static class CameraImport
{
    public const string Command = "import-cameras";

    /// <summary>How many consecutive runs of one source may miss a camera it previously
    /// reported before that source's own entry stops protecting the camera from retirement (see
    /// <see cref="Camera.MarkSourceMissing"/>). A design decision for issue #369: high enough
    /// that one bad/partial extract (an OOM'd run, a temporary upstream gap) doesn't retire real
    /// cameras, low enough that a camera genuinely removed from a source's data doesn't linger
    /// forever. Revisit once real run cadence for each source is established.</summary>
    private const int RetireAfterConsecutiveMisses = 3;

    public static async Task RunAsync(string jsonPath, IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var repo = scope.ServiceProvider.GetRequiredService<ICameraRepository>();

        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath));
        var source = doc.RootElement.GetProperty("source").GetString()!;
        var region = doc.RootElement.TryGetProperty("region", out var rg) && rg.ValueKind == JsonValueKind.String ? rg.GetString() : null;
        var now = DateTimeOffset.UtcNow;
        var imported = 0;
        var skipped = 0;
        var index = 0;
        var seenSourceIds = new HashSet<string>();

        foreach (var el in doc.RootElement.GetProperty("cameras").EnumerateArray())
        {
            var entryIndex = index++;

            // Only JSON parsing + entity construction is caught here — a structurally malformed
            // entry (missing property, unrecognized `kind`, non-numeric lat/lon, …) is counted
            // and skipped rather than aborting the whole run: with hundreds/thousands of
            // OSM-derived entries per region (Task 8), one bad row must not discard every entry
            // already processed (all DB writes are deferred to the single FlushChangesAsync
            // below, so an uncaught throw here would have flushed nothing at all, not just
            // skipped this entry). `repo.UpsertAsync` below is deliberately OUTSIDE this try: a
            // database failure (connection down, auth error) is a run-aborting problem, not a
            // per-entry data problem, and must propagate — swallowing it as "skipped: invalid"
            // would tell an operator their JSON was bad when the real issue was the import never
            // reached the database at all.
            Result<Camera> result;
            try
            {
                var kind = Enum.Parse<CameraKind>(el.GetProperty("kind").GetString()!);
                var sourceId = el.GetProperty("sourceId").GetString()!;
                var maxSpeed = el.TryGetProperty("maxSpeedKmh", out var ms) && ms.ValueKind != JsonValueKind.Null ? ms.GetInt32() : (int?)null;
                var roadRef = el.TryGetProperty("roadRef", out var rr) && rr.ValueKind != JsonValueKind.Null ? rr.GetString() : null;
                var cameraSource = new CameraSource(source, sourceId, now, now, Region: region);
                seenSourceIds.Add(sourceId);

                result = el.TryGetProperty("polyline", out var poly) && poly.ValueKind == JsonValueKind.Array
                    ? Camera.CreateSection(kind, poly.EnumerateArray().Select(p => (p[0].GetDouble(), p[1].GetDouble())).ToList(), maxSpeed, roadRef, cameraSource)
                    : Camera.CreatePoint(kind, el.GetProperty("lat").GetDouble(), el.GetProperty("lon").GetDouble(), maxSpeed, roadRef, cameraSource);
            }
            catch (Exception ex)
            {
                skipped++;
                var badSourceId = el.TryGetProperty("sourceId", out var sid) && sid.ValueKind == JsonValueKind.String
                    ? sid.GetString()
                    : "<unreadable>";
                // The source still reports this camera, just in a shape we can't read — that is
                // not "stopped reporting", so it must not count as a miss toward retirement.
                if (badSourceId != "<unreadable>") seenSourceIds.Add(badSourceId!);
                Console.Error.WriteLine($"skipping cameras[{entryIndex}] (sourceId={badSourceId}): {ex.Message}");
                continue;
            }

            if (result.IsFailure) { skipped++; continue; }

            await repo.UpsertAsync(result.Value, CancellationToken.None);
            imported++;
        }

        await repo.FlushChangesAsync(CancellationToken.None);

        // A run that imported nothing (empty `cameras`, every entry invalid) is a broken extract,
        // not a source that stopped reporting everything — retiring from it would charge every
        // camera that source ever reported a miss. Issue #390.
        // ponytail: only catches the all-or-nothing case; a run that is merely much smaller than
        // the last one still retires normally — add a per-source minimum-count check if that bites.
        var retired = 0;
        if (imported == 0)
        {
            Console.Error.WriteLine($"{Path.GetFileName(jsonPath)}: imported 0 cameras for source '{source}' — skipping retirement, this run can't tell a missing camera from a broken extract");
        }
        else
        {
            retired = await repo.RetireMissingAsync(source, region, seenSourceIds, RetireAfterConsecutiveMisses, CancellationToken.None);
            await repo.FlushChangesAsync(CancellationToken.None);
        }

        Console.WriteLine($"{Path.GetFileName(jsonPath)}: upserted {imported}, skipped {skipped} invalid, retired {retired}");
    }
}
