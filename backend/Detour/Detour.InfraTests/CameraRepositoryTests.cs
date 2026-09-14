using Detour.Database.Repositories;
using Detour.Domain.Cameras;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class CameraRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    private static CameraSource OsmSource(string id) => new("osm", id, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);

    [Fact]
    public async Task UpsertAsync_of_two_close_same_kind_cameras_merges_into_one_row()
    {
        var repo = new CameraRepository(Factory);

        var first = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85000, 4.36000, 50, "N9", OsmSource("n1")).Value;
        await repo.UpsertAsync(first, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        // ~12 m away — inside the 40 m cluster radius.
        var second = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85011, 4.36000, 50, "N9", OsmSource("n2")).Value;
        var stored = await repo.UpsertAsync(second, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        var all = await repo.BboxAsync(50.84, 4.35, 50.86, 4.37, CancellationToken.None);
        Assert.Single(all);
        Assert.Equal(2, all[0].Sources.Count);
    }

    [Fact]
    public async Task BboxAsync_excludes_cameras_outside_the_box()
    {
        var repo = new CameraRepository(Factory);
        // A different city entirely from the upsert-merge test above (50.85, 4.36, Belgium) —
        // this class shares one live table across its tests with no truncation between them
        // (the same convention SchemaTests uses via random per-test data), and a query box wide
        // enough to prove exclusion would otherwise risk catching that test's rows too,
        // regardless of which test happens to run first.
        var inside = Camera.CreatePoint(CameraKind.FixedSpeed, 48.85, 2.35, 50, "N9", OsmSource("n3")).Value;
        var outside = Camera.CreatePoint(CameraKind.FixedSpeed, 49.90, 2.35, 50, "N9", OsmSource("n4")).Value;
        await repo.UpsertAsync(inside, CancellationToken.None);
        await repo.UpsertAsync(outside, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, CancellationToken.None);

        Assert.Single(found);
        Assert.Equal(inside.Id, found[0].Id);
    }
}
