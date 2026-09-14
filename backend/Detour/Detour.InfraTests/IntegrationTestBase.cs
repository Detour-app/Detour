using Detour.Database;
using Detour.Database.Configuration;
using Detour.InfraTests.Database;
using Shared.Database;

namespace Detour.InfraTests;

/// <summary>
/// Base for repository-level integration tests against the shared Postgres container. A
/// concrete test class gives itself the <see cref="PostgresCollection.Name"/> collection and a
/// constructor that forwards its injected <see cref="PostgresFixture"/> here; <see cref="Factory"/>
/// then builds contexts against that fixture's connection the same way <c>DatabaseInstaller</c>
/// wires <see cref="DetourDbContextFactory"/> into the running API.
/// </summary>
public abstract class IntegrationTestBase(PostgresFixture postgres) : IAsyncLifetime
{
    protected ICustomDbContextFactory<DetourDbContext> Factory { get; } =
        new DetourDbContextFactory(new DatabaseSettings(), postgres.ConnectionString);

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync() => await Factory.DisposeAsync();
}
