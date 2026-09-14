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

    public static async Task RunAsync(string jsonPath, IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var repo = scope.ServiceProvider.GetRequiredService<ICameraRepository>();

        var doc = JsonDocument.Parse(await File.ReadAllTextAsync(jsonPath));
        var source = doc.RootElement.GetProperty("source").GetString()!;
        var now = DateTimeOffset.UtcNow;
        var imported = 0;
        var skipped = 0;
        var index = 0;

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
                var cameraSource = new CameraSource(source, sourceId, now, now);

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
                Console.Error.WriteLine($"skipping cameras[{entryIndex}] (sourceId={badSourceId}): {ex.Message}");
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
