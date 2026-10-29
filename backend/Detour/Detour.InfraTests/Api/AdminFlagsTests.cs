using System.Net;
using System.Net.Http.Json;
using Detour.Api.Contracts;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// <c>api/admin/flags</c> (#813): the owner switches a default for everyone or an override for one
/// account, and <c>GET /api/flags</c> answers accordingly for that account only.
///
/// The database is shared across the collection, so every test uses its own keys.
/// </summary>
[Collection(PostgresCollection.Name)]
public class AdminFlagsTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_ordinary_rider_is_refused_every_call()
    {
        var rider = await _factory.SignInAsync();
        var key = NewKey();

        (await rider.GetAsync("/api/admin/flags")).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await rider.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = true })).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
        (await rider.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{rider.UserId}", new { enabled = true }))
            .StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await rider.DeleteAsync($"/api/admin/flags/{key}")).StatusCode.Should().Be(HttpStatusCode.Forbidden);

        await using var db = postgres.CreateContext();
        (await db.FeatureFlags.AnyAsync(f => f.Key == key)).Should().BeFalse();
    }

    [Fact]
    public async Task A_default_is_created_switched_and_deleted()
    {
        var admin = await NewAdministrator();
        var key = NewKey();

        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = false })).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Flags.Should().ContainSingle(f => f.Key == key)
            .Which.Enabled.Should().BeFalse();

        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = true })).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Flags.Should().ContainSingle(f => f.Key == key)
            .Which.Enabled.Should().BeTrue();

        (await admin.DeleteAsync($"/api/admin/flags/{key}")).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Flags.Should().NotContain(f => f.Key == key);
        (await admin.DeleteAsync($"/api/admin/flags/{key}")).StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task An_override_is_created_switched_listed_with_its_account_and_deleted()
    {
        var admin = await NewAdministrator();
        var rider = await _factory.SignInAsync();
        var key = NewKey();
        await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = false });
        var path = $"/api/admin/flags/{key}/accounts/{rider.UserId}";

        (await admin.PutAsJsonAsync(path, new { enabled = true })).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Overrides.Should().ContainSingle(o => o.Key == key)
            .Which.Should().Be(new AdminFlagOverrideResponse(key, rider.UserId, rider.Username, true));

        (await admin.PutAsJsonAsync(path, new { enabled = false })).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Overrides.Should().ContainSingle(o => o.Key == key)
            .Which.Enabled.Should().BeFalse();

        (await admin.DeleteAsync(path)).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await AdminList(admin)).Overrides.Should().NotContain(o => o.Key == key);
        (await admin.DeleteAsync(path)).StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task An_override_for_an_unknown_flag_or_account_is_not_found()
    {
        var admin = await NewAdministrator();
        var rider = await _factory.SignInAsync();
        var key = NewKey();

        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{rider.UserId}", new { enabled = true }))
            .StatusCode.Should().Be(HttpStatusCode.NotFound, "the flag has no default yet");

        await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = false });
        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{Guid.NewGuid()}", new { enabled = true }))
            .StatusCode.Should().Be(HttpStatusCode.NotFound, "no such account");
    }

    [Theory]
    [InlineData("Not-Lowercase")]
    [InlineData("under_score")]
    [InlineData("a-key-that-is-far-too-long-to-be-a-flag-because-it-runs-past-sixty-four")]
    public async Task A_malformed_key_is_a_bad_request(string key)
    {
        var admin = await NewAdministrator();
        var rider = await _factory.SignInAsync();

        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = true })).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
        (await admin.DeleteAsync($"/api/admin/flags/{key}")).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await admin.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{rider.UserId}", new { enabled = true }))
            .StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await admin.DeleteAsync($"/api/admin/flags/{key}/accounts/{rider.UserId}")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task What_the_admin_switches_reaches_the_right_account_only()
    {
        var admin = await NewAdministrator();
        var owner = await _factory.SignInAsync();
        var other = await _factory.SignInAsync();
        var key = NewKey();

        await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = false });
        await admin.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{owner.UserId}", new { enabled = true });

        (await FlagFor(owner.Client, key)).Should().Be(new FlagResponse(key, true, "account"));
        (await FlagFor(other.Client, key)).Should().Be(new FlagResponse(key, false, "default"));
        (await FlagFor(_factory.CreateClient(), key)).Should().Be(new FlagResponse(key, false, "default"));

        await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = true });
        (await FlagFor(other.Client, key)).Should().Be(new FlagResponse(key, true, "default"));
    }

    [Fact]
    public async Task Deleting_a_flag_takes_its_overrides()
    {
        var admin = await NewAdministrator();
        var rider = await _factory.SignInAsync();
        var key = NewKey();
        await admin.PutAsJsonAsync($"/api/admin/flags/{key}", new { enabled = false });
        await admin.PutAsJsonAsync($"/api/admin/flags/{key}/accounts/{rider.UserId}", new { enabled = true });

        (await admin.DeleteAsync($"/api/admin/flags/{key}")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.FeatureFlagOverrides.AnyAsync(o => o.FlagKey == key)).Should().BeFalse();
        (await rider.GetFromJsonAsync<FlagsResponse>("/api/flags"))!.Flags.Should().NotContain(f => f.Key == key);
    }

    private static string NewKey() => $"test-{Guid.NewGuid():N}";

    private async Task<SignedInClient> NewAdministrator()
    {
        var username = $"admin{Guid.NewGuid():N}"[..16];
        var client = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-user", "detour-admin"));

        var admin = new SignedInClient(client, username);
        await admin.ResolveUserIdAsync();
        return admin;
    }

    private static async Task<AdminFlagsResponse> AdminList(SignedInClient admin)
    {
        var response = await admin.GetAsync("/api/admin/flags");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<AdminFlagsResponse>())!;
    }

    private static async Task<FlagResponse> FlagFor(HttpClient client, string key)
    {
        var flags = (await client.GetFromJsonAsync<FlagsResponse>("/api/flags"))!.Flags;
        return flags.Single(f => f.Key == key);
    }
}
