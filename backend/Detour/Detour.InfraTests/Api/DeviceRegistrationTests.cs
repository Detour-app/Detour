using System.Net;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// Regression coverage for #149: two concurrent `PUT /api/devices` registering
/// the *same brand-new token* used to both read null from
/// `IDeviceTokenRepository.GetByTokenAsync`, both insert, and let the unique
/// index on `token` turn the second insert into a `DbUpdateException` -> 500.
/// Real Postgres, not a mock - the whole failure is a real unique-index
/// violation (SQLSTATE 23505), which <see cref="PostgresFixture"/> exists to
/// reproduce and the InMemory provider cannot.
/// </summary>
[Collection(PostgresCollection.Name)]
public class DeviceRegistrationTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public async Task DisposeAsync() => await _factory.DisposeAsync();

    [Fact]
    public async Task Two_concurrent_registrations_of_the_same_new_token_both_succeed()
    {
        var rider = await _factory.SignInAsync();
        var token = $"race-token-{Guid.NewGuid():N}";

        // Neither request has landed yet, so a read-then-write implementation
        // has both read "not found" by the time either writes - exactly the
        // race #149 describes, forced deterministically by not awaiting
        // either PUT before starting the other.
        var first = rider.PutAsJsonAsync("/api/devices", new { token, platform = "android" });
        var second = rider.PutAsJsonAsync("/api/devices", new { token, platform = "android" });
        var responses = await Task.WhenAll(first, second);

        foreach (var response in responses)
            response.StatusCode.Should().Be(HttpStatusCode.NoContent);
    }
}
