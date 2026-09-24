using Detour.Api;
using Detour.Domain.Cameras;
using Microsoft.Extensions.DependencyInjection;
using Moq;

namespace Detour.InfraTests.Api;

/// <summary>Issue #390: an import run that can't vouch for its "missing" set must not charge
/// cameras a miss toward retirement.</summary>
public class CameraImportTests : IDisposable
{
    private readonly string _path = Path.GetTempFileName();
    private readonly Mock<ICameraRepository> _repo = new();

    public CameraImportTests()
    {
        _repo.Setup(r => r.UpsertAsync(It.IsAny<Camera>(), It.IsAny<CancellationToken>()))
            .ReturnsAsync((Camera c, CancellationToken _) => c);
    }

    public void Dispose() => File.Delete(_path);

    private async Task RunAsync(string json)
    {
        await File.WriteAllTextAsync(_path, json);
        var services = new ServiceCollection().AddSingleton(_repo.Object).BuildServiceProvider();
        await CameraImport.RunAsync(_path, services);
    }

    [Fact]
    public async Task RunAsync_with_no_cameras_skips_retirement()
    {
        await RunAsync("""{ "source": "osm", "cameras": [] }""");

        _repo.Verify(r => r.RetireMissingAsync(It.IsAny<string>(), It.IsAny<IReadOnlySet<string>>(), It.IsAny<int>(), It.IsAny<CancellationToken>()), Times.Never);
    }

    [Fact]
    public async Task RunAsync_where_every_entry_is_invalid_skips_retirement()
    {
        await RunAsync("""{ "source": "osm", "cameras": [ { "kind": "NotAKind", "sourceId": "n1", "lat": 50.8, "lon": 4.3 } ] }""");

        _repo.Verify(r => r.RetireMissingAsync(It.IsAny<string>(), It.IsAny<IReadOnlySet<string>>(), It.IsAny<int>(), It.IsAny<CancellationToken>()), Times.Never);
    }

    [Fact]
    public async Task RunAsync_counts_an_unparseable_entry_with_a_readable_sourceId_as_seen()
    {
        await RunAsync("""
            { "source": "osm", "cameras": [
              { "kind": "FixedSpeed", "sourceId": "n1", "lat": 50.8, "lon": 4.3 },
              { "kind": "NotAKind", "sourceId": "n2", "lat": 50.9, "lon": 4.4 }
            ] }
            """);

        _repo.Verify(r => r.RetireMissingAsync("osm",
            It.Is<IReadOnlySet<string>>(s => s.SetEquals(new[] { "n1", "n2" })),
            It.IsAny<int>(), It.IsAny<CancellationToken>()), Times.Once);
    }
}
