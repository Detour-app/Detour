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
        // (#367): PoiImport does one FlushChangesAsync at the very end of a run, not one per
        // entry.
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
}
