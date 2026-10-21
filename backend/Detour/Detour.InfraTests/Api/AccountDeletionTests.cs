using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Configuration;
using Detour.Api.Services;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace Detour.InfraTests.Api;

/// <summary>
/// A rider deleting their own account (issue #700): the same cascade as the administrator's
/// delete, then a best-effort note to the realm administrator. The webhook itself is a capturing
/// stand-in here; what it sends is covered in <see cref="AccountDeletionNotifierTests"/>.
/// </summary>
[Collection(PostgresCollection.Name)]
public class AccountDeletionTests(PostgresFixture postgres) : IAsyncLifetime
{
    private readonly CapturingNotifier _notifier = new();
    private DetourApiFactory _factory = null!;
    private WebApplicationFactory<Program> _web = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        _web = _factory.WithWebHostBuilder(builder =>
            builder.ConfigureTestServices(services =>
            {
                services.RemoveAll<IAccountDeletionNotifier>();
                services.AddSingleton<IAccountDeletionNotifier>(_notifier);
            }));
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _web.DisposeAsync();
        await _factory.DisposeAsync();
    }

    [Fact]
    public async Task Deleting_your_own_account_takes_everything_it_owns()
    {
        var (rider, subject, username) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { new { startTimeMs = 1_000L } },
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            savedPlaces = new[] { new { id = 7L, name = "Home" } },
            badges = new Dictionary<string, long> { ["dist_100000"] = 1_000 },
            tripTitles = new[] { new { startTimeMs = 1_000L, title = "Coast road", editedAtMs = 2_000L } },
        });
        await rider.PostAsJsonAsync("/api/me/api-keys", new { label = "dashboard" });
        var riderId = await IdOf(rider);

        (await rider.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.Users.AnyAsync(u => u.Id == riderId)).Should().BeFalse();
        (await db.Trips.AnyAsync(t => t.UserId == riderId)).Should().BeFalse();
        (await db.Traces.AnyAsync(t => t.UserId == riderId)).Should().BeFalse();
        (await db.TrackPoints.AnyAsync(p => p.UserId == riderId)).Should().BeFalse();
        (await db.SavedPlaces.AnyAsync(p => p.UserId == riderId)).Should().BeFalse();
        (await db.BadgeAwards.AnyAsync(b => b.UserId == riderId)).Should().BeFalse();
        (await db.TripTitles.AnyAsync(t => t.UserId == riderId)).Should().BeFalse();
        (await db.ApiKeys.AnyAsync(k => k.UserId == riderId)).Should().BeFalse();

        var note = await _notifier.WaitForFirst();
        note.Subject.Should().Be(subject);
        note.Username.Should().Be(username);
    }

    [Fact]
    public async Task Deleting_your_own_account_leaves_everyone_else_alone()
    {
        var (leaver, _, _) = await NewRider();
        var (stayer, _, _) = await NewRider();
        var stayerId = await IdOf(stayer);

        (await leaver.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.Users.AnyAsync(u => u.Id == stayerId)).Should().BeTrue();
        (await stayer.GetAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Signing_in_again_after_deleting_starts_an_empty_account()
    {
        // The realm user outlives the delete until its administrator removes it; a token for it
        // must not bring the old data back.
        var (rider, _, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new { trips = new[] { new { startTimeMs = 1_000L } } });
        var before = await IdOf(rider);

        (await rider.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        var after = await IdOf(rider);
        after.Should().NotBe(before);
        await using var db = postgres.CreateContext();
        (await db.Trips.AnyAsync(t => t.UserId == after)).Should().BeFalse();
    }

    [Fact]
    public async Task An_administrator_may_delete_their_own_account_here()
    {
        // The admin endpoint refuses self-deletion to stop a lockout mid-operation; the role
        // lives in the realm, so deleting the app data locks nobody out of administering.
        var (admin, _, _) = await NewRider("detour-user", "detour-admin");

        (await admin.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    private async Task<(HttpClient Client, string Subject, string Username)> NewRider(params string[] roles)
    {
        var subject = $"subject-{Guid.NewGuid():N}";
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _web.CreateClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", _factory.IssueToken(
            subject, username, null, roles.Length == 0 ? ["detour-user"] : roles));
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, subject, username);
    }

    private static async Task<Guid> IdOf(HttpClient client) =>
        (await client.GetFromJsonAsync<JsonElement>("/api/me")).GetProperty("id").GetGuid();

    private sealed class CapturingNotifier : IAccountDeletionNotifier
    {
        private readonly ConcurrentQueue<DeletedAccount> _notes = new();

        public Task NotifyAsync(DeletedAccount account)
        {
            _notes.Enqueue(account);
            return Task.CompletedTask;
        }

        /// <summary>Runs post-commit, so poll briefly rather than assume it already ran.</summary>
        public async Task<DeletedAccount> WaitForFirst()
        {
            for (var attempt = 0; attempt < 50; attempt++)
            {
                if (_notes.TryPeek(out var note))
                    return note;
                await Task.Delay(100);
            }

            throw new Xunit.Sdk.XunitException("No deletion notice was sent within 5s.");
        }
    }
}

/// <summary>What the webhook receives, and that no answer from it can fail a deletion.</summary>
public class AccountDeletionNotifierTests
{
    private static readonly DeletedAccount Deleted =
        new("subject-1", "rider1", DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000));

    [Fact]
    public async Task Nothing_is_sent_when_no_webhook_is_configured()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));

        await Notifier(handler, "").NotifyAsync(Deleted);

        handler.Requests.Should().BeEmpty();
    }

    [Fact]
    public async Task The_webhook_gets_the_subject_and_handle_and_no_email()
    {
        string? body = null;
        var handler = new StubHandler(request =>
        {
            body = request.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
            return new HttpResponseMessage(HttpStatusCode.NoContent);
        });

        await Notifier(handler, "https://hooks.example/detour").NotifyAsync(Deleted);

        handler.Requests.Should().ContainSingle().Which.Method.Should().Be(HttpMethod.Post);
        handler.Requests[0].RequestUri.Should().Be(new Uri("https://hooks.example/detour"));
        var json = JsonDocument.Parse(body!).RootElement;
        json.GetProperty("event").GetString().Should().Be("account_deleted");
        json.GetProperty("subject").GetString().Should().Be("subject-1");
        json.GetProperty("username").GetString().Should().Be("rider1");
        json.GetProperty("deletedAtMs").GetInt64().Should().Be(1_700_000_000_000);
        json.TryGetProperty("email", out _).Should().BeFalse();
    }

    [Fact]
    public async Task An_error_answer_does_not_throw()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.InternalServerError));

        var notify = () => Notifier(handler, "https://hooks.example/detour").NotifyAsync(Deleted);

        await notify.Should().NotThrowAsync();
    }

    [Fact]
    public async Task An_unreachable_webhook_does_not_throw()
    {
        var handler = new StubHandler(_ => throw new HttpRequestException("connection refused"));

        var notify = () => Notifier(handler, "https://hooks.example/detour").NotifyAsync(Deleted);

        await notify.Should().NotThrowAsync();
    }

    private static AccountDeletionNotifier Notifier(StubHandler handler, string url) =>
        new(StubHandler.Factory(handler),
            Options.Create(new AccountDeletionSettings { WebhookUrl = url }),
            NullLogger<AccountDeletionNotifier>.Instance);
}
