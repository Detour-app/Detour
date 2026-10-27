using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// <c>POST /api/problem-reports</c> (#818): stored with or without an account, a 401 rather
/// than an anonymous report for a token that doesn't validate, field and body caps, and the
/// write-time retention.
///
/// The database is shared across the collection, so every test finds its own reports by the
/// note or id it sent.
/// </summary>
[Collection(PostgresCollection.Name)]
public class ProblemReportsTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_anonymous_report_is_stored_with_no_account()
    {
        var response = await _factory.CreateClient().PostAsJsonAsync("/api/problem-reports", Report());

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var id = await IdOf(response);
        await using var db = postgres.CreateContext();
        var stored = await db.ProblemReports.SingleAsync(r => r.Id == id);
        stored.UserId.Should().BeNull();
        stored.Kind.Should().Be("crash");
        JsonSerializer.Deserialize<string[]>(stored.Events).Should().Equal("service start: boot");
    }

    [Fact]
    public async Task A_signed_in_report_is_linked_to_the_caller()
    {
        var rider = await _factory.SignInAsync();

        var response = await rider.PostAsJsonAsync("/api/problem-reports", Report());

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var id = await IdOf(response);
        await using var db = postgres.CreateContext();
        (await db.ProblemReports.SingleAsync(r => r.Id == id)).UserId.Should().Be(rider.UserId);
    }

    [Fact]
    public async Task A_token_that_does_not_validate_is_refused_not_stored_as_anonymous()
    {
        var client = _factory.CreateClientWith(_factory.IssueForeignToken("subject-foreign", "foreigner"));
        var note = Unique();

        var response = await client.PostAsJsonAsync("/api/problem-reports", Report(note));

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
        await using var db = postgres.CreateContext();
        (await db.ProblemReports.AnyAsync(r => r.Note == note)).Should().BeFalse();
    }

    [Fact]
    public async Task A_note_over_the_cap_is_a_400()
    {
        var response = await _factory.CreateClient()
            .PostAsJsonAsync("/api/problem-reports", Report(new string('a', 2001)));

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_body_over_512_KB_is_a_413()
    {
        // Every field under its own cap except the body as a whole: four event lines of 140 KB
        // would be refused as 400 for the log, so the size goes in the stack trace and the log.
        var report = Report() with
        {
            StackTrace = new string('a', 60 * 1024),
            Events = [new string('b', 250 * 1024)],
            Padding = new string('c', 300 * 1024),
        };

        // Kestrel enforces the limit; the in-memory test server ignores it and would store this.
        _factory.UseKestrel(0);
        _factory.StartServer();
        var response = await _factory.CreateClient().PostAsJsonAsync("/api/problem-reports", report);

        response.StatusCode.Should().Be(HttpStatusCode.RequestEntityTooLarge);
    }

    [Fact]
    public async Task A_report_older_than_the_retention_is_gone_after_the_next_insert()
    {
        var client = _factory.CreateClient();
        var old = await IdOf(await client.PostAsJsonAsync("/api/problem-reports", Report()));
        await using (var db = postgres.CreateContext())
        {
            await db.ProblemReports.Where(r => r.Id == old)
                .ExecuteUpdateAsync(s => s.SetProperty(r => r.ReceivedAt, DateTimeOffset.UtcNow.AddDays(-31)));
        }

        (await client.PostAsJsonAsync("/api/problem-reports", Report())).EnsureSuccessStatusCode();

        await using var after = postgres.CreateContext();
        (await after.ProblemReports.AnyAsync(r => r.Id == old)).Should().BeFalse();
    }

    [Fact]
    public async Task MaxRows_keeps_only_the_newest_reports()
    {
        await using var web = _factory.WithWebHostBuilder(b => b.UseSetting("ProblemReports:MaxRows", "3"));
        var client = web.CreateClient();

        var ids = new List<Guid>();
        for (var i = 0; i < 4; i++)
            ids.Add(await IdOf(await client.PostAsJsonAsync("/api/problem-reports", Report())));

        await using var db = postgres.CreateContext();
        var left = await db.ProblemReports.Select(r => r.Id).ToListAsync();
        left.Should().BeEquivalentTo(ids.Skip(1));
    }

    [Fact]
    public async Task Deleting_the_account_removes_its_reports()
    {
        var rider = await _factory.SignInAsync();
        (await rider.PostAsJsonAsync("/api/problem-reports", Report())).EnsureSuccessStatusCode();

        (await rider.DeleteAsync("/api/me")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.ProblemReports.AnyAsync(r => r.UserId == rider.UserId)).Should().BeFalse();
    }

    private static string Unique() => $"note-{Guid.NewGuid():N}";

    private static ReportBody Report(string? note = null) => new(
        Kind: "crash",
        Platform: "android",
        AppVersion: "3.90.0",
        OsVersion: "Android 15",
        DeviceModel: "Pixel 8",
        OccurredAt: 1_761_000_000_000,
        Note: note ?? Unique(),
        StackTrace: "java.lang.IllegalStateException",
        Events: ["service start: boot"]);

    private static async Task<Guid> IdOf(HttpResponseMessage response)
    {
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    /// <summary>The wire shape, plus a field the API ignores so a test can grow the body alone.</summary>
    private sealed record ReportBody(
        string Kind,
        string Platform,
        string AppVersion,
        string OsVersion,
        string DeviceModel,
        long OccurredAt,
        string? Note,
        string? StackTrace,
        IReadOnlyList<string> Events,
        string? Padding = null);
}
