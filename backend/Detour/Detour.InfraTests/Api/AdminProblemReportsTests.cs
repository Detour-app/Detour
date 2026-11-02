using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// <c>api/admin/problem-reports</c> (#820): the loop host lists what it has not filed and marks it
/// filed. Administrators only, a service account with no rider row included, and nothing in the
/// answer names the account.
///
/// The database is shared across the collection, so every test finds its own reports by id.
/// </summary>
[Collection(PostgresCollection.Name)]
public class AdminProblemReportsTests(PostgresFixture postgres) : IAsyncLifetime
{
    private const string List = "/api/admin/problem-reports";
    private const string Issue = "https://github.com/Detour-app/Detour/issues/900";

    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_ordinary_rider_is_refused()
    {
        var rider = await _factory.SignInAsync();

        (await rider.GetAsync(List)).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await rider.PostAsJsonAsync($"{List}/{Guid.NewGuid()}/filed", new { issue = Issue })).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task A_service_account_with_no_rider_row_can_list()
    {
        // Keycloak's service accounts are named service-account-<client>, which the handle rule
        // refuses; provisioning one as a rider would fail the request.
        var username = $"service-account-detour-loop-{Guid.NewGuid():N}";
        var loop = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-admin"));
        var id = await Send(_factory.CreateClient());

        var response = await loop.GetAsync($"{List}?filed=false&limit=200");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await Ids(response)).Should().Contain(id);
        await using var db = postgres.CreateContext();
        (await db.Users.AnyAsync(u => u.Username == username)).Should().BeFalse();
    }

    [Fact]
    public async Task Filed_false_leaves_out_filed_reports()
    {
        var admin = Administrator();
        var filed = await Send(_factory.CreateClient());
        var open = await Send(_factory.CreateClient());

        (await admin.PostAsJsonAsync($"{List}/{filed}/filed", new { issue = Issue })).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        var unfiled = await Ids(await admin.GetAsync($"{List}?filed=false&limit=200"));
        unfiled.Should().Contain(open).And.NotContain(filed);
        (await Ids(await admin.GetAsync($"{List}?limit=200"))).Should().Contain(filed, "no filter lists both");
    }

    [Fact]
    public async Task Marking_filed_twice_keeps_the_first_issue()
    {
        var admin = Administrator();
        var id = await Send(_factory.CreateClient());

        (await admin.PostAsJsonAsync($"{List}/{id}/filed", new { issue = Issue })).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
        await using var first = postgres.CreateContext();
        var filedAt = (await first.ProblemReports.SingleAsync(r => r.Id == id)).FiledAt;

        (await admin.PostAsJsonAsync($"{List}/{id}/filed", new { issue = Issue + "1" })).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        var stored = await db.ProblemReports.SingleAsync(r => r.Id == id);
        stored.FiledAt.Should().Be(filedAt);
        stored.FiledIssue.Should().Be(Issue);
    }

    [Fact]
    public async Task Marking_an_unknown_report_is_a_404()
    {
        (await Administrator().PostAsJsonAsync($"{List}/{Guid.NewGuid()}/filed", new { issue = Issue })).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task A_filed_issue_that_is_not_an_https_url_is_a_400()
    {
        var id = await Send(_factory.CreateClient());

        (await Administrator().PostAsJsonAsync($"{List}/{id}/filed", new { issue = "900" })).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task The_list_prunes_reports_past_retention_before_it_reads()
    {
        var old = await Send(_factory.CreateClient());
        await using (var db = postgres.CreateContext())
        {
            await db.ProblemReports.Where(r => r.Id == old)
                .ExecuteUpdateAsync(s => s.SetProperty(r => r.ReceivedAt, DateTimeOffset.UtcNow.AddDays(-31)));
        }

        (await Administrator().GetAsync(List)).StatusCode.Should().Be(HttpStatusCode.OK);

        await using var after = postgres.CreateContext();
        (await after.ProblemReports.AnyAsync(r => r.Id == old)).Should().BeFalse();
    }

    [Fact]
    public async Task Limit_and_after_page_oldest_first()
    {
        var admin = Administrator();
        var client = _factory.CreateClient();
        var a = await Send(client);
        var b = await Send(client);
        var c = await Send(client);

        (await Ids(await admin.GetAsync($"{List}?filed=false&after={a}&limit=1"))).Should().Equal(b);
        (await Ids(await admin.GetAsync($"{List}?filed=false&after={b}&limit=5"))).Should().Equal(c);
        (await Ids(await admin.GetAsync($"{List}?filed=false&after={a}"))).Should().Equal(b, c);
    }

    [Fact]
    public async Task Empty_query_values_list_everything_from_the_start()
    {
        // What the Bruno request sends with its variables left blank.
        var id = await Send(_factory.CreateClient());

        (await Ids(await Administrator().GetAsync($"{List}?filed=&limit=200&after="))).Should().Contain(id);
    }

    [Fact]
    public async Task A_cursor_that_is_gone_is_a_400()
    {
        (await Administrator().GetAsync($"{List}?after={Guid.NewGuid()}")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_report_carries_its_device_and_content_and_never_the_account()
    {
        var rider = await _factory.SignInAsync();
        var id = await Send(rider.Client);

        var report = await Find(Administrator(), id);

        report.GetProperty("kind").GetString().Should().Be("crash");
        report.GetProperty("deviceModel").GetString().Should().Be("Pixel 8");
        report.GetProperty("occurredAt").GetInt64().Should().Be(1_761_000_000_000);
        report.GetProperty("events").EnumerateArray().Select(e => e.GetString()).Should().Equal("service start: boot");
        report.GetProperty("filedAt").ValueKind.Should().Be(JsonValueKind.Null);
        var raw = report.GetRawText();
        raw.Should().NotContain(rider.UserId.ToString()).And.NotContain(rider.Username);
        foreach (var forbidden in new[] { "userId", "username", "email" })
            report.TryGetProperty(forbidden, out _).Should().BeFalse($"an administrator cannot see {forbidden}");
    }

    [Fact]
    public async Task The_reporter_is_stable_per_account_different_across_accounts_and_null_when_anonymous()
    {
        var admin = Administrator();
        var one = await _factory.SignInAsync();
        var other = await _factory.SignInAsync();
        var first = await Send(one.Client);
        var second = await Send(one.Client);
        var others = await Send(other.Client);
        var anonymous = await Send(_factory.CreateClient());

        var reporter = Reporter(await Find(admin, first));

        reporter.Should().NotBeNullOrEmpty();
        Reporter(await Find(admin, second)).Should().Be(reporter);
        Reporter(await Find(admin, others)).Should().NotBe(reporter).And.NotBeNull();
        Reporter(await Find(admin, anonymous)).Should().BeNull();
    }

    [Fact]
    public async Task The_reporter_survives_a_restart()
    {
        var rider = await _factory.SignInAsync();
        var id = await Send(rider.Client);
        var before = Reporter(await Find(Administrator(), id));

        await using var restarted = new DetourApiFactory(postgres);
        var after = Reporter(await Find(Administrator(restarted), id));

        after.Should().NotBeNull().And.Be(before);
    }

    [Fact]
    public async Task A_configured_secret_is_used_instead_of_the_stored_one()
    {
        var rider = await _factory.SignInAsync();
        var id = await Send(rider.Client);
        var stored = Reporter(await Find(Administrator(), id));

        await using var configured = _factory.WithWebHostBuilder(
            b => b.UseSetting("ProblemReports:ReporterKeySecret", "a self-hoster's own secret"));
        var withSecret = configured.CreateClient();
        withSecret.DefaultRequestHeaders.Authorization = new("Bearer", Token(_factory));

        Reporter(await Find(withSecret, id)).Should().NotBe(stored).And.NotBeNull();
    }

    private HttpClient Administrator(DetourApiFactory? factory = null) =>
        (factory ?? _factory).CreateClientWith(Token(factory ?? _factory));

    private static string Token(DetourApiFactory factory) => factory.IssueToken(
        $"subject-{Guid.NewGuid():N}", $"admin{Guid.NewGuid():N}"[..16], null, "detour-admin");

    private static async Task<Guid> Send(HttpClient client)
    {
        var response = await client.PostAsJsonAsync("/api/problem-reports", new
        {
            kind = "crash",
            platform = "android",
            appVersion = "3.90.0",
            osVersion = "Android 15",
            deviceModel = "Pixel 8",
            occurredAt = 1_761_000_000_000,
            note = $"note-{Guid.NewGuid():N}",
            stackTrace = "java.lang.IllegalStateException",
            events = new[] { "service start: boot" },
        });
        response.StatusCode.Should().Be(HttpStatusCode.Created);
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    private static async Task<JsonElement> Find(HttpClient admin, Guid id)
    {
        var response = await admin.GetAsync($"{List}?limit=200");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("reports").EnumerateArray().Single(r => r.GetProperty("id").GetGuid() == id);
    }

    private static async Task<List<Guid>> Ids(HttpResponseMessage response)
    {
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return [.. body.GetProperty("reports").EnumerateArray().Select(r => r.GetProperty("id").GetGuid())];
    }

    private static string? Reporter(JsonElement report) => report.GetProperty("reporter").GetString();
}

