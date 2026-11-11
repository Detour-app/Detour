using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Services;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.InfraTests.Api;

/// <summary>
/// <c>/api/me/here-key</c> (#904, spec on #802): the rider's HERE key stored as Data Protection
/// ciphertext on their account. Each test runs against a key ring in its own directory, the way
/// docker/prod mounts one, so a second API built over the same directory stands in for a
/// container recreate.
/// </summary>
[Collection(PostgresCollection.Name)]
public class HereTrafficKeyTests(PostgresFixture postgres) : IAsyncLifetime
{
    private const string Route = "/api/me/here-key";

    private readonly string _keysPath =
        Path.Combine(Path.GetTempPath(), $"detour-dp-{Guid.NewGuid():N}");

    private DetourApiFactory _factory = null!;
    private WebApplicationFactory<Program> _web = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        _web = WithKeyRing(_factory);
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _web.DisposeAsync();
        await _factory.DisposeAsync();
        if (Directory.Exists(_keysPath))
            Directory.Delete(_keysPath, recursive: true);
    }

    [Fact]
    public async Task A_stored_key_reads_back_trimmed()
    {
        var rider = await SignIn(_web);

        (await rider.Client.PutAsJsonAsync(Route, new { key = "  here-key-123  " }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);

        var body = await GetBody(rider);
        body.GetProperty("key").GetString().Should().Be("here-key-123");
        body.TryGetProperty("unreadable", out _).Should().BeFalse();
    }

    [Fact]
    public async Task No_key_is_204_and_a_deleted_one_is_204_again()
    {
        var rider = await SignIn(_web);
        (await rider.Client.GetAsync(Route)).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await rider.Client.PutAsJsonAsync(Route, new { key = "here-key-123" });
        (await rider.Client.DeleteAsync(Route)).StatusCode.Should().Be(HttpStatusCode.NoContent);

        (await rider.Client.GetAsync(Route)).StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public async Task A_blank_key_is_refused(string? key)
    {
        var rider = await SignIn(_web);

        var response = await rider.Client.PutAsJsonAsync(Route, new { key });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await rider.Client.GetAsync(Route)).StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task An_oversized_key_is_refused_and_a_256_character_one_is_not()
    {
        var rider = await SignIn(_web);

        (await rider.Client.PutAsJsonAsync(Route, new { key = new string('k', 257) }))
            .StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await rider.Client.PutAsJsonAsync(Route, new { key = new string('k', 256) }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task The_stored_column_is_not_the_key()
    {
        var rider = await SignIn(_web);
        await rider.Client.PutAsJsonAsync(Route, new { key = "here-key-plaintext" });

        await using var db = postgres.CreateContext();
        var stored = await db.Users.Where(u => u.Id == rider.Id)
            .Select(u => u.HereTrafficKeyProtected).SingleAsync();

        stored.Should().NotBeNullOrEmpty();
        stored.Should().NotContain("here-key-plaintext");
    }

    [Fact]
    public async Task The_key_is_not_in_the_profile()
    {
        var rider = await SignIn(_web);
        await rider.Client.PutAsJsonAsync(Route, new { key = "here-key-in-profile" });

        var me = await rider.Client.GetStringAsync("/api/me");

        me.Should().NotContain("here-key-in-profile");
        me.Should().NotContainEquivalentOf("hereTrafficKey");
    }

    [Fact]
    public async Task A_key_from_another_key_ring_reads_back_unreadable_until_a_put_replaces_it()
    {
        var rider = await SignIn(_web);
        var foreign = new EphemeralDataProtectionProvider()
            .CreateProtector(HereTrafficKeyProtector.Purpose)
            .Protect("lost-ring-key");
        await using (var db = postgres.CreateContext())
        {
            await db.Users.Where(u => u.Id == rider.Id)
                .ExecuteUpdateAsync(s => s.SetProperty(u => u.HereTrafficKeyProtected, foreign));
        }

        var unreadable = await GetBody(rider);
        unreadable.GetProperty("key").ValueKind.Should().Be(JsonValueKind.Null);
        unreadable.GetProperty("unreadable").GetBoolean().Should().BeTrue();

        // A GET leaves it in place: the phone that still holds the key is the one to put it back.
        (await GetBody(rider)).GetProperty("unreadable").GetBoolean().Should().BeTrue();

        await rider.Client.PutAsJsonAsync(Route, new { key = "re-uploaded-key" });
        (await GetBody(rider)).GetProperty("key").GetString().Should().Be("re-uploaded-key");
    }

    [Fact]
    public async Task The_key_survives_a_new_api_over_the_same_key_ring()
    {
        var rider = await SignIn(_web);
        await rider.Client.PutAsJsonAsync(Route, new { key = "survives-recreate" });

        // A second API process over the same directory. Its token signer differs from the first
        // one's, so this decrypts the stored row with its protector rather than over HTTP.
        await using var recreatedFactory = new DetourApiFactory(postgres);
        await using var recreated = WithKeyRing(recreatedFactory);
        var protector = recreated.Services.GetRequiredService<IHereTrafficKeyProtector>();
        await using var db = postgres.CreateContext();
        var stored = await db.Users.Where(u => u.Id == rider.Id)
            .Select(u => u.HereTrafficKeyProtected).SingleAsync();

        protector.TryUnprotect(stored!).Should().Be("survives-recreate");
        Directory.EnumerateFiles(_keysPath, "key-*.xml").Should().NotBeEmpty();
    }

    [Fact]
    public async Task Deleting_the_account_takes_the_key_with_it()
    {
        var rider = await SignIn(_web);
        await rider.Client.PutAsJsonAsync(Route, new { key = "goes-with-the-account" });

        (await rider.Client.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.Users.AnyAsync(u => u.Id == rider.Id)).Should().BeFalse();
    }

    private WebApplicationFactory<Program> WithKeyRing(DetourApiFactory factory) =>
        factory.WithWebHostBuilder(builder => builder.UseSetting("DataProtection:KeysPath", _keysPath));

    private async Task<Rider> SignIn(WebApplicationFactory<Program> web)
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = web.CreateClient();
        client.DefaultRequestHeaders.Authorization =
            new("Bearer", _factory.IssueToken($"subject-{Guid.NewGuid():N}", username, null, "detour-user"));

        var me = await client.GetFromJsonAsync<JsonElement>("/api/me");
        return new Rider(client, me.GetProperty("id").GetGuid());
    }

    private static async Task<JsonElement> GetBody(Rider rider)
    {
        var response = await rider.Client.GetAsync(Route);
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private sealed record Rider(HttpClient Client, Guid Id);
}
