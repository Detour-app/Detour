using Detour.Database;
using Detour.Database.Configuration;
using Microsoft.EntityFrameworkCore;
using Testcontainers.PostgreSql;

namespace Detour.InfraTests.Database;

/// <summary>
/// A real Postgres for the tests that need one. The InMemory provider cannot reproduce any of
/// what this backend actually leans on: citext comparison, jsonb columns, snake_case naming, or
/// a unique-index violation surfacing as SQLSTATE 23505.
///
/// One container for the whole collection — container startup costs seconds, and every test in
/// here reads the same schema.
/// </summary>
public sealed class PostgresFixture : IAsyncLifetime
{
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder("postgres:18-alpine")
        .WithDatabase("detour")
        .Build();

    public string ConnectionString => _container.GetConnectionString();

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        await using var db = CreateContext();
        await db.Database.MigrateAsync();
    }

    public async Task DisposeAsync() => await _container.DisposeAsync();

    public DetourDbContext CreateContext() =>
        new DetourDbContextFactory(new DatabaseSettings(), ConnectionString).CreateNew();
}

[CollectionDefinition(Name)]
public sealed class PostgresCollection : ICollectionFixture<PostgresFixture>
{
    public const string Name = "postgres";
}
