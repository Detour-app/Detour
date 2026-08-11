using System.Net;
using System.Net.Http.Json;
using Detour.Database;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class AuthenticationTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_unauthenticated_request_is_refused()
    {
        var response = await _factory.CreateClient().GetAsync("/api/me");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task A_token_signed_by_an_unknown_key_is_refused()
    {
        var token = _factory.IssueForeignToken(Subject(), Handle());

        var response = await _factory.CreateClientWith(token).GetAsync("/api/me");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Health_is_reachable_without_a_token()
    {
        var response = await _factory.CreateClient().GetAsync("/api/health");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task The_first_token_for_a_subject_provisions_its_account()
    {
        // This is what replaces registration: the identity provider decides who may exist, and
        // the backend records the row everything else keys on.
        var subject = Subject();
        var handle = Handle();
        var token = _factory.IssueToken(subject, handle, "rider@detour.invalid", "detour-user");

        var response = await _factory.CreateClientWith(token).GetAsync("/api/me");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var me = await response.Content.ReadFromJsonAsync<MePayload>();
        me!.Username.Should().Be(handle);
        me.Email.Should().Be("rider@detour.invalid");
        me.ShareFog.Should().BeFalse("sharing is off until a rider turns it on");
        me.IsAdministrator.Should().BeFalse();

        await using var db = postgres.CreateContext();
        (await db.Users.CountAsync(u => u.Subject == subject)).Should().Be(1);
    }

    [Fact]
    public async Task A_second_request_reuses_the_account_rather_than_creating_another()
    {
        var subject = Subject();
        var token = _factory.IssueToken(subject, Handle(), null, "detour-user");
        var client = _factory.CreateClientWith(token);

        var first = await (await client.GetAsync("/api/me")).Content.ReadFromJsonAsync<MePayload>();
        var second = await (await client.GetAsync("/api/me")).Content.ReadFromJsonAsync<MePayload>();

        second!.Id.Should().Be(first!.Id);

        await using var db = postgres.CreateContext();
        (await db.Users.CountAsync(u => u.Subject == subject)).Should().Be(1);
    }

    [Fact]
    public async Task The_administrator_role_is_read_from_the_token_on_every_request()
    {
        // Not from the stored row: revoking the role in the identity provider has to take
        // effect on the next token, not whenever the row happens to be rewritten.
        var subject = Subject();
        var handle = Handle();

        var asAdmin = _factory.CreateClientWith(
            _factory.IssueToken(subject, handle, null, "detour-user", "detour-admin"));
        var elevated = await (await asAdmin.GetAsync("/api/me")).Content.ReadFromJsonAsync<MePayload>();
        elevated!.IsAdministrator.Should().BeTrue();

        var asRider = _factory.CreateClientWith(
            _factory.IssueToken(subject, handle, null, "detour-user"));
        var demoted = await (await asRider.GetAsync("/api/me")).Content.ReadFromJsonAsync<MePayload>();
        demoted!.IsAdministrator.Should().BeFalse();
    }

    [Fact]
    public async Task Turning_fog_sharing_on_is_committed()
    {
        // Also proves the transaction middleware commits a mutation made through the aggregate
        // without the handler calling SaveChanges itself.
        var subject = Subject();
        var client = _factory.CreateClientWith(_factory.IssueToken(subject, Handle(), null, "detour-user"));
        await client.GetAsync("/api/me");

        var put = await client.PutAsJsonAsync("/api/me/fog-sharing", new { shareFog = true });
        put.StatusCode.Should().Be(HttpStatusCode.OK);

        var reread = await (await client.GetAsync("/api/me")).Content.ReadFromJsonAsync<MePayload>();
        reread!.ShareFog.Should().BeTrue();

        await using var db = postgres.CreateContext();
        var stored = await db.Users.SingleAsync(u => u.Subject == subject);
        stored.ShareFog.Should().BeTrue();
    }

    private static string Subject() => $"subject-{Guid.NewGuid():N}";

    private static string Handle() => $"rider{Guid.NewGuid():N}"[..16];

    private sealed record MePayload(
        Guid Id,
        string Username,
        string? Email,
        bool ShareFog,
        bool IsAdministrator);
}
