using System.Text.Json;
using Detour.Domain.Cameras;
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

        foreach (var el in doc.RootElement.GetProperty("cameras").EnumerateArray())
        {
            var kind = Enum.Parse<CameraKind>(el.GetProperty("kind").GetString()!);
            var sourceId = el.GetProperty("sourceId").GetString()!;
            var maxSpeed = el.TryGetProperty("maxSpeedKmh", out var ms) && ms.ValueKind != JsonValueKind.Null ? ms.GetInt32() : (int?)null;
            var roadRef = el.TryGetProperty("roadRef", out var rr) && rr.ValueKind != JsonValueKind.Null ? rr.GetString() : null;
            var cameraSource = new CameraSource(source, sourceId, now, now);

            var result = el.TryGetProperty("polyline", out var poly) && poly.ValueKind == JsonValueKind.Array
                ? Camera.CreateSection(kind, poly.EnumerateArray().Select(p => (p[0].GetDouble(), p[1].GetDouble())).ToList(), maxSpeed, roadRef, cameraSource)
                : Camera.CreatePoint(kind, el.GetProperty("lat").GetDouble(), el.GetProperty("lon").GetDouble(), maxSpeed, roadRef, cameraSource);

            if (result.IsFailure) { skipped++; continue; }

            await repo.UpsertAsync(result.Value, CancellationToken.None);
            imported++;
        }

        await repo.FlushChangesAsync(CancellationToken.None);
        Console.WriteLine($"{Path.GetFileName(jsonPath)}: upserted {imported}, skipped {skipped} invalid");
    }
}
