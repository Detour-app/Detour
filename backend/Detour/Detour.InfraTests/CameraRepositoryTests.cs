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
    public async Task UpsertAsync_of_two_close_same_kind_cameras_merges_even_without_a_flush_between_them()
    {
        // Reproduces CameraImport's actual usage — one FlushChangesAsync at the very end of an
        // import run, not one per entry (#367). Without Set.Local in the candidate query, the
        // second UpsertAsync can't see the first's not-yet-flushed row and inserts a duplicate.
        var repo = new CameraRepository(Factory);

        var first = Camera.CreatePoint(CameraKind.FixedSpeed, 50.87000, 4.38000, 50, "N9", OsmSource("n5")).Value;
        await repo.UpsertAsync(first, CancellationToken.None);

        // ~12 m away — inside the 40 m cluster radius.
        var second = Camera.CreatePoint(CameraKind.FixedSpeed, 50.87011, 4.38000, 50, "N9", OsmSource("n6")).Value;
        var stored = await repo.UpsertAsync(second, CancellationToken.None);

        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        var all = await repo.BboxAsync(50.86, 4.37, 50.88, 4.39, CancellationToken.None);
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

    [Fact]
    public async Task RetireMissingAsync_retires_a_camera_whose_only_source_stopped_reporting_it()
    {
        var repo = new CameraRepository(Factory);
        // A source name unique to this test — RetireMissingAsync scans every active camera in
        // the shared table (no truncation between tests, see BboxAsync_excludes above), so an
        // unrelated test's "osm" rows must never be touched by this call.
        const string source = "retire-test-lone-source";
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 51.10, 3.10, 50, "N9",
            new CameraSource(source, "r1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow)).Value;
        await repo.UpsertAsync(cam, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        for (var i = 0; i < 3; i++)
        {
            await repo.RetireMissingAsync(source, new HashSet<string>(), retireAfterMisses: 3, CancellationToken.None);
            await repo.FlushChangesAsync(CancellationToken.None);
        }

        var found = await repo.BboxAsync(51.09, 3.09, 51.11, 3.11, CancellationToken.None);
        Assert.Empty(found); // BboxAsync only returns Active cameras
    }

    [Fact]
    public async Task RetireMissingAsync_leaves_a_camera_active_when_its_sourceId_was_seen_this_run()
    {
        var repo = new CameraRepository(Factory);
        const string source = "retire-test-still-seen";
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 51.20, 3.20, 50, "N9",
            new CameraSource(source, "r2", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow)).Value;
        await repo.UpsertAsync(cam, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        await repo.RetireMissingAsync(source, new HashSet<string> { "r2" }, retireAfterMisses: 1, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.BboxAsync(51.19, 3.19, 51.21, 3.21, CancellationToken.None);
        Assert.Single(found);
    }
}
