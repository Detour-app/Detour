using System.Net;
using Detour.Domain.Notifications;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

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

    [Fact]
    public async Task A_token_carrying_SQL_is_stored_as_data_and_executes_nothing()
    {
        // UpsertAsync is the only raw SQL in this backend, and `token` is the
        // only attacker-controlled value in it. DeviceToken.Create validates
        // length and non-emptiness and filters no characters, so a payload like
        // this reaches the statement untouched - parameterization is the sole
        // defence, which is what this pins. ASVS 5.0.0 V1.2.4, CWE-89.
        var rider = await _factory.SignInAsync();
        var hostile = $"evil-{Guid.NewGuid():N}'); DROP TABLE detour.device_tokens; --";

        var response = await rider.PutAsJsonAsync(
            "/api/devices", new { token = hostile, platform = "android" });

        response.StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        // Reaching the table at all proves the DROP never ran; the value coming
        // back byte-for-byte proves it crossed as a bound parameter rather than
        // as statement text.
        var stored = await db.Set<DeviceToken>()
            .SingleOrDefaultAsync(t => t.Token == hostile);

        stored.Should().NotBeNull("the payload must round-trip as an ordinary row");
        stored!.Token.Should().Be(hostile);
    }
}
