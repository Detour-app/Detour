using Detour.Database.Repositories;
using Detour.Domain.Boundaries;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class MunicipalityBoundaryRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    private static List<(double, double)> Square(double lat, double lon) =>
        [(lat, lon), (lat, lon + 0.02), (lat + 0.02, lon + 0.02), (lat + 0.02, lon)];

    private static IReadOnlyList<IReadOnlyList<(double, double)>> Rings(IReadOnlyList<(double, double)> ring) => [ring];

    [Fact]
    public async Task UpsertAsync_of_a_new_source_id_inserts_a_new_row()
    {
        var repo = new MunicipalityBoundaryRepository(Factory);

        var boundary = MunicipalityBoundary.Create("r-new-1", "Testville", Rings(Square(50.80, 4.30))).Value;
        var stored = await repo.UpsertAsync(boundary, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(boundary.Id, stored.Id);
        var found = await repo.CandidatesAsync(50.81, 4.31, CancellationToken.None);
        Assert.Contains(found, b => b.Id == boundary.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_the_existing_row_rather_than_duplicating_it()
    {
        var repo = new MunicipalityBoundaryRepository(Factory);

        var first = MunicipalityBoundary.Create("r-replace-1", "Oldname", Rings(Square(50.90, 4.40))).Value;
        await repo.UpsertAsync(first, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var incoming = MunicipalityBoundary.Create("r-replace-1", "Newname", Rings(Square(50.90, 4.40))).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("Newname", stored.Name);
        var found = await repo.CandidatesAsync(50.91, 4.41, CancellationToken.None);
        Assert.Single(found, b => b.Id == first.Id);
    }

    [Fact]
    public async Task UpsertAsync_of_the_same_source_id_replaces_even_without_a_flush_between_them()
    {
        // Same reason SpeedLimitWayRepositoryTests' equivalent exists (#367): MunicipalityImport
        // does one FlushChangesAsync at the very end of a run, not one per entry.
        var repo = new MunicipalityBoundaryRepository(Factory);

        var first = MunicipalityBoundary.Create("r-replace-2", "Oldname", Rings(Square(50.95, 4.45))).Value;
        await repo.UpsertAsync(first, CancellationToken.None);

        var incoming = MunicipalityBoundary.Create("r-replace-2", "Newname", Rings(Square(50.95, 4.45))).Value;
        var stored = await repo.UpsertAsync(incoming, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        Assert.Equal(first.Id, stored.Id);
        Assert.Equal("Newname", stored.Name);
    }

    [Fact]
    public async Task CandidatesAsync_excludes_boundaries_whose_bbox_does_not_contain_the_point()
    {
        var repo = new MunicipalityBoundaryRepository(Factory);
        var inside = MunicipalityBoundary.Create("r-inside-1", "Inside", Rings(Square(48.85, 2.35))).Value;
        var outside = MunicipalityBoundary.Create("r-outside-1", "Outside", Rings(Square(10.00, 10.00))).Value;
        await repo.UpsertAsync(inside, CancellationToken.None);
        await repo.UpsertAsync(outside, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);

        var found = await repo.CandidatesAsync(48.86, 2.36, CancellationToken.None);

        Assert.Contains(found, b => b.Id == inside.Id);
        Assert.DoesNotContain(found, b => b.Id == outside.Id);
    }
}
