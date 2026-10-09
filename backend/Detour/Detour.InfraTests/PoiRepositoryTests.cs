using Detour.Database.Repositories;
using Detour.Domain.Pois;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class PoiRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    [Fact]
    public async Task UpsertAsync_of_a_new_source_id_inserts_a_new_row()
    {
        var repo = new PoiRepository(Factory);

        var poi = Poi.Create("n-new-1", "viewpoint", "Belvedere", 50.85, 4.36).Value;
        var stored = await repo.UpsertAsync(poi, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(poi.Id, stored.Id);
        var found = await repo.BboxAsync(50.84, 4.35, 50.86, 4.37, null, CancellationToken.None);
        Assert.Contains(found, p => p.Id == poi.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_the_existing_row_rather_than_duplicating_it()
    {
        var repo = new PoiRepository(Factory);

        var first = Poi.Create("n-replace-1", "food", "Old Cafe", 50.90, 4.40).Value;
        await repo.UpsertAsync(first, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var incoming = Poi.Create("n-replace-1", "food", "New Cafe", 50.90, 4.40).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("New Cafe", stored.Name);
        var found = await repo.BboxAsync(50.89, 4.39, 50.91, 4.41, null, CancellationToken.None);
        Assert.Single(found, p => p.Id == first.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_even_without_a_flush_between_them()
    {
        // Same reason RoadWayRepositoryTests'/SpeedLimitWayRepositoryTests' equivalent exists
        // (#367): a caller may upsert several POIs before one FlushChangesAsync.
        var repo = new PoiRepository(Factory);

        var first = Poi.Create("n-replace-2", "sight", "Old Fort", 50.95, 4.45).Value;
        await repo.UpsertAsync(first, CancellationToken.None);

        var incoming = Poi.Create("n-replace-2", "sight", "New Fort", 50.95, 4.45).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("New Fort", stored.Name);
    }

    [Fact]
    public async Task BboxAsync_excludes_pois_outside_the_box()
    {
        var repo = new PoiRepository(Factory);
        var inside = Poi.Create("n-inside-1", "viewpoint", "In", 48.85, 2.35).Value;
        var outside = Poi.Create("n-outside-1", "viewpoint", "Out", 10.00, 10.00).Value;
        await repo.UpsertAsync(inside, CancellationToken.None);
        await repo.UpsertAsync(outside, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, null, CancellationToken.None);

        Assert.Contains(found, p => p.Id == inside.Id);
        Assert.DoesNotContain(found, p => p.Id == outside.Id);
    }

    [Fact]
    public async Task BboxAsync_with_a_kind_filter_excludes_a_poi_of_a_different_kind()
    {
        var repo = new PoiRepository(Factory);
        var viewpoint = Poi.Create("n-kind-1", "viewpoint", "View", 48.85, 2.35).Value;
        var food = Poi.Create("n-kind-2", "food", "Cafe", 48.85, 2.35).Value;
        await repo.UpsertAsync(viewpoint, CancellationToken.None);
        await repo.UpsertAsync(food, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(48.80, 2.30, 48.90, 2.40, ["viewpoint"], CancellationToken.None);

        Assert.Contains(found, p => p.Id == viewpoint.Id);
        Assert.DoesNotContain(found, p => p.Id == food.Id);
    }

    [Fact]
    public async Task UpsertBatchAsync_replaces_an_existing_row_and_collapses_a_duplicate_within_the_batch()
    {
        var repo = new PoiRepository(Factory);
        var first = Poi.Create("n-batch-1", "food", "Old Cafe", 51.50, 5.50).Value;
        await repo.UpsertBatchAsync([first], CancellationToken.None);

        await repo.UpsertBatchAsync(
            [
                Poi.Create("n-batch-1", "food", "New Cafe", 51.50, 5.50).Value,
                Poi.Create("n-batch-2", "sight", "Old Tower", 51.50, 5.50).Value,
                Poi.Create("n-batch-2", "sight", "New Tower", 51.50, 5.50).Value,
            ],
            CancellationToken.None);

        var found = await repo.BboxAsync(51.49, 5.49, 51.51, 5.51, null, CancellationToken.None);
        var replaced = Assert.Single(found, p => p.SourceId == "n-batch-1");
        Assert.Equal(first.Id, replaced.Id);
        Assert.Equal("New Cafe", replaced.Name);
        Assert.Equal("New Tower", Assert.Single(found, p => p.SourceId == "n-batch-2").Name);
    }

    [Fact]
    public async Task UpsertBatchAsync_imports_thousands_of_rows_across_batches()
    {
        // #561: the per-row path was O(n²) and a 36k-row extract never finished. Five batches of
        // a thousand, re-upserted once, must land every row exactly once.
        var repo = new PoiRepository(Factory);
        Poi[] Rows(string name) =>
        [
            .. Enumerable.Range(0, 5000)
                .Select(i => Poi.Create($"n-bulk-{i}", "viewpoint", name, 52.0 + i * 1e-5, 6.0).Value),
        ];

        foreach (var batch in Rows("First").Chunk(1000))
            await repo.UpsertBatchAsync(batch, CancellationToken.None);
        foreach (var batch in Rows("Second").Chunk(1000))
            await repo.UpsertBatchAsync(batch, CancellationToken.None);

        var found = await repo.BboxAsync(51.99, 5.99, 52.06, 6.01, null, CancellationToken.None);
        var bulk = found.Where(p => p.SourceId.StartsWith("n-bulk-")).ToList();
        Assert.Equal(5000, bulk.Count);
        Assert.All(bulk, p => Assert.Equal("Second", p.Name));
    }
}
