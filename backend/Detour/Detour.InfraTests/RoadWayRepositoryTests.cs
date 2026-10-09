using Detour.Database.Repositories;
using Detour.Domain.Roads;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class RoadWayRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    private static (double, double)[] Polyline(double lat, double lon) => [(lat, lon), (lat + 0.001, lon + 0.001)];

    [Fact]
    public async Task UpsertAsync_of_a_new_source_id_inserts_a_new_row()
    {
        var repo = new RoadWayRepository(Factory);

        var way = RoadWay.Create("w-new-1", Polyline(50.85, 4.36), "primary").Value;
        var stored = await repo.UpsertAsync(way, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(way.Id, stored.Id);
        var found = await repo.BboxAsync(50.84, 4.35, 50.86, 4.37, null, CancellationToken.None);
        Assert.Contains(found, w => w.Id == way.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_the_existing_row_rather_than_duplicating_it()
    {
        var repo = new RoadWayRepository(Factory);

        var first = RoadWay.Create("w-replace-1", Polyline(50.90, 4.40), "residential").Value;
        await repo.UpsertAsync(first, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var incoming = RoadWay.Create("w-replace-1", Polyline(50.90, 4.40), "primary").Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("primary", stored.Highway);
        var found = await repo.BboxAsync(50.89, 4.39, 50.91, 4.41, null, CancellationToken.None);
        Assert.Single(found, w => w.Id == first.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_even_without_a_flush_between_them()
    {
        // Same reason CameraRepositoryTests'/SpeedLimitWayRepositoryTests' equivalent exists
        // (#367): a caller may upsert several ways before one FlushChangesAsync.
        var repo = new RoadWayRepository(Factory);

        var first = RoadWay.Create("w-replace-2", Polyline(50.95, 4.45), "residential").Value;
        await repo.UpsertAsync(first, CancellationToken.None);

        var incoming = RoadWay.Create("w-replace-2", Polyline(50.95, 4.45), "primary").Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("primary", stored.Highway);
    }

    [Fact]
    public async Task BboxAsync_excludes_ways_outside_the_box()
    {
        var repo = new RoadWayRepository(Factory);
        var inside = RoadWay.Create("w-inside-1", Polyline(48.85, 2.35), "primary").Value;
        var outside = RoadWay.Create("w-outside-1", Polyline(10.00, 10.00), "primary").Value;
        await repo.UpsertAsync(inside, CancellationToken.None);
        await repo.UpsertAsync(outside, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, null, CancellationToken.None);

        Assert.Contains(found, w => w.Id == inside.Id);
        Assert.DoesNotContain(found, w => w.Id == outside.Id);
    }

    [Fact]
    public async Task BboxAsync_with_a_classes_filter_excludes_a_way_of_a_different_class()
    {
        var repo = new RoadWayRepository(Factory);
        var primary = RoadWay.Create("w-class-1", Polyline(48.85, 2.35), "primary").Value;
        var residential = RoadWay.Create("w-class-2", Polyline(48.85, 2.35), "residential").Value;
        await repo.UpsertAsync(primary, CancellationToken.None);
        await repo.UpsertAsync(residential, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, ["primary"], CancellationToken.None);

        Assert.Contains(found, w => w.Id == primary.Id);
        Assert.DoesNotContain(found, w => w.Id == residential.Id);
    }

    [Fact]
    public async Task UpsertBatchAsync_replaces_an_existing_row_and_collapses_a_duplicate_within_the_batch()
    {
        var repo = new RoadWayRepository(Factory);
        var first = RoadWay.Create("w-batch-1", Polyline(51.50, 5.50), "residential").Value;
        await repo.UpsertBatchAsync([first], CancellationToken.None);

        await repo.UpsertBatchAsync(
            [
                RoadWay.Create("w-batch-1", Polyline(51.50, 5.50), "primary").Value,
                RoadWay.Create("w-batch-2", Polyline(51.50, 5.50), "tertiary").Value,
                RoadWay.Create("w-batch-2", Polyline(51.50, 5.50), "secondary").Value,
            ],
            CancellationToken.None);

        var found = await repo.BboxAsync(51.49, 5.49, 51.51, 5.51, null, CancellationToken.None);
        var replaced = Assert.Single(found, w => w.SourceId == "w-batch-1");
        Assert.Equal(first.Id, replaced.Id);
        Assert.Equal("primary", replaced.Highway);
        Assert.Equal("secondary", Assert.Single(found, w => w.SourceId == "w-batch-2").Highway);
    }
}
