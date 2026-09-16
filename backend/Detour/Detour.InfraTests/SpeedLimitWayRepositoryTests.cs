using Detour.Database.Repositories;
using Detour.Domain.Roads;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class SpeedLimitWayRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    private static (double, double)[] Polyline(double lat, double lon) => [(lat, lon), (lat + 0.001, lon + 0.001)];

    [Fact]
    public async Task UpsertAsync_of_a_new_source_id_inserts_a_new_row()
    {
        var repo = new SpeedLimitWayRepository(Factory);

        var way = SpeedLimitWay.Create("w-new-1", Polyline(50.85, 4.36), 70).Value;
        var stored = await repo.UpsertAsync(way, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(way.Id, stored.Id);
        var found = await repo.BboxAsync(50.84, 4.35, 50.86, 4.37, CancellationToken.None);
        Assert.Contains(found, w => w.Id == way.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_the_existing_row_rather_than_duplicating_it()
    {
        var repo = new SpeedLimitWayRepository(Factory);

        var first = SpeedLimitWay.Create("w-replace-1", Polyline(50.90, 4.40), 50).Value;
        await repo.UpsertAsync(first, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var incoming = SpeedLimitWay.Create("w-replace-1", Polyline(50.90, 4.40), 90).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal(90, stored.MaxSpeedKmh);
        var found = await repo.BboxAsync(50.89, 4.39, 50.91, 4.41, CancellationToken.None);
        Assert.Single(found, w => w.Id == first.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_even_without_a_flush_between_them()
    {
        // Same reason CameraRepositoryTests' equivalent exists (#367): SpeedLimitImport does
        // one FlushChangesAsync at the very end of a run, not one per entry.
        var repo = new SpeedLimitWayRepository(Factory);

        var first = SpeedLimitWay.Create("w-replace-2", Polyline(50.95, 4.45), 50).Value;
        await repo.UpsertAsync(first, CancellationToken.None);

        var incoming = SpeedLimitWay.Create("w-replace-2", Polyline(50.95, 4.45), 90).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal(90, stored.MaxSpeedKmh);
    }

    [Fact]
    public async Task BboxAsync_excludes_ways_outside_the_box()
    {
        var repo = new SpeedLimitWayRepository(Factory);
        var inside = SpeedLimitWay.Create("w-inside-1", Polyline(48.85, 2.35), 50).Value;
        var outside = SpeedLimitWay.Create("w-outside-1", Polyline(10.00, 10.00), 50).Value;
        await repo.UpsertAsync(inside, CancellationToken.None);
        await repo.UpsertAsync(outside, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, CancellationToken.None);

        Assert.Contains(found, w => w.Id == inside.Id);
        Assert.DoesNotContain(found, w => w.Id == outside.Id);
    }
}
