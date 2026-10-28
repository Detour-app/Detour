using System.Net;
using System.Net.Http.Json;
using Detour.Api.Contracts;
using Detour.Domain.FeatureFlags;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// <c>GET /api/flags</c> (#812): the defaults for anyone, an account's overrides in their place
/// for that account, and a 401 rather than the defaults for a token that doesn't validate.
///
/// The database is shared across the collection, so every test seeds its own keys and reads only
/// those back.
/// </summary>
[Collection(PostgresCollection.Name)]
public class FlagsTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_anonymous_caller_gets_the_defaults()
    {
        var owner = await _factory.SignInAsync();
        var key = await SeedFlag(enabled: false, overrides: (owner.UserId, true));

        var response = await _factory.CreateClient().GetAsync("/api/flags");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var flags = (await response.Content.ReadFromJsonAsync<FlagsResponse>())!.Flags;
        flags.Should().ContainSingle(f => f.Key == key)
            .Which.Should().Be(new FlagResponse(key, false, "default"));
    }

    [Fact]
    public async Task A_signed_in_account_with_an_override_gets_its_own_value()
    {
        var owner = await _factory.SignInAsync();
        var key = await SeedFlag(enabled: false, overrides: (owner.UserId, true));

        var flags = await FlagsFor(owner.Client);

        flags.Should().ContainSingle(f => f.Key == key)
            .Which.Should().Be(new FlagResponse(key, true, "account"));
    }

    [Fact]
    public async Task A_signed_in_account_without_an_override_gets_the_default()
    {
        var owner = await _factory.SignInAsync();
        var other = await _factory.SignInAsync();
        var key = await SeedFlag(enabled: true, overrides: (owner.UserId, false));

        var flags = await FlagsFor(other.Client);

        flags.Should().ContainSingle(f => f.Key == key)
            .Which.Should().Be(new FlagResponse(key, true, "default"));
    }

    [Fact]
    public async Task A_key_without_a_row_is_not_listed()
    {
        // The app keeps its compiled default for a key the server doesn't send.
        var flags = await FlagsFor(_factory.CreateClient());

        flags.Should().NotContain(f => f.Key == "flags-never-seeded");
    }

    [Fact]
    public async Task A_token_that_does_not_validate_is_refused_not_answered_with_defaults()
    {
        // Answering it as anonymous would let an expired token cache the defaults over the
        // account's overrides; a 401 sends the app through its refresh-and-retry instead.
        var client = _factory.CreateClientWith(_factory.IssueForeignToken("subject-foreign", "foreigner"));

        var response = await client.GetAsync("/api/flags");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Deleting_the_account_removes_its_overrides()
    {
        var owner = await _factory.SignInAsync();
        var key = await SeedFlag(enabled: false, overrides: (owner.UserId, true));

        (await owner.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.FeatureFlagOverrides.AnyAsync(o => o.UserId == owner.UserId)).Should().BeFalse();
        (await db.FeatureFlags.AnyAsync(f => f.Key == key)).Should().BeTrue("the flag is everyone's");
    }

    private static async Task<IReadOnlyList<FlagResponse>> FlagsFor(HttpClient client)
    {
        var response = await client.GetAsync("/api/flags");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<FlagsResponse>())!.Flags;
    }

    private async Task<string> SeedFlag(bool enabled, params (Guid UserId, bool Enabled)[] overrides)
    {
        var key = $"test-{Guid.NewGuid():N}";
        var flag = FeatureFlag.Create(key, enabled).Value;

        await using var db = postgres.CreateContext();
        db.FeatureFlags.Add(flag);
        db.FeatureFlagOverrides.AddRange(overrides.Select(o => flag.OverrideFor(o.UserId, o.Enabled)));
        await db.SaveChangesAsync();
        return key;
    }
}
