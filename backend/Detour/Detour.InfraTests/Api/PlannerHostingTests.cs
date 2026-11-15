using System.Net;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Detour.InfraTests.Api;

/// <summary>
/// The web planner served under <c>/planner</c> (#913): its files from <c>Planner:Path</c>, the
/// index for any page path below it, and nothing at all when no build is configured.
/// </summary>
[Collection(PostgresCollection.Name)]
public class PlannerHostingTests(PostgresFixture postgres) : IAsyncLifetime
{
    private const string Index = "<!doctype html><title>planner test index</title>";
    private const string Script = "console.log('planner');";

    private readonly string _root = Path.Combine(Path.GetTempPath(), $"detour-planner-{Guid.NewGuid():N}");
    private DetourApiFactory _factory = null!;
    private WebApplicationFactory<Program> _served = null!;

    public Task InitializeAsync()
    {
        Directory.CreateDirectory(Path.Combine(_root, "assets"));
        File.WriteAllText(Path.Combine(_root, "index.html"), Index);
        File.WriteAllText(Path.Combine(_root, "assets", "index-abc123.js"), Script);

        _factory = new DetourApiFactory(postgres);
        _served = _factory.WithWebHostBuilder(b => b.UseSetting("Planner:Path", _root));
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _served.DisposeAsync();
        await _factory.DisposeAsync();
        Directory.Delete(_root, recursive: true);
    }

    private HttpClient Client() =>
        _served.CreateClient(new WebApplicationFactoryClientOptions { AllowAutoRedirect = false });

    [Fact]
    public async Task The_planner_root_returns_the_index_without_a_token()
    {
        var response = await Client().GetAsync("/planner/");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.Headers.ContentType!.MediaType.Should().Be("text/html");
        (await response.Content.ReadAsStringAsync()).Should().Be(Index);
        response.Headers.CacheControl!.NoCache.Should().BeTrue();
    }

    [Theory]
    [InlineData("/planner/routes/1731500000000")]
    [InlineData("/planner/x")]
    public async Task An_unknown_page_path_falls_back_to_the_index(string path)
    {
        var response = await Client().GetAsync(path);

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await response.Content.ReadAsStringAsync()).Should().Be(Index);
    }

    [Fact]
    public async Task A_hashed_asset_is_served_and_cached_for_good()
    {
        var response = await Client().GetAsync("/planner/assets/index-abc123.js");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await response.Content.ReadAsStringAsync()).Should().Be(Script);
        response.Headers.CacheControl!.ToString().Should().Contain("immutable");
    }

    [Fact]
    public async Task A_missing_asset_is_a_404_not_the_index()
    {
        var response = await Client().GetAsync("/planner/assets/index-old999.js");

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task The_bare_path_redirects_to_the_slash()
    {
        var response = await Client().GetAsync("/planner?x=1");

        response.StatusCode.Should().Be(HttpStatusCode.Redirect);
        response.Headers.Location!.OriginalString.Should().Be("/planner/?x=1");
    }

    [Fact]
    public async Task Pages_carry_a_strict_content_security_policy()
    {
        var response = await Client().GetAsync("/planner/");

        var csp = response.Headers.GetValues("Content-Security-Policy").Single();
        csp.Should().Contain("script-src 'self'");
        csp.Should().NotContain("unsafe-inline");
        csp.Should().Contain("frame-ancestors 'none'");
        // Discovery and the token exchange go to the realm, on its own origin in this setup.
        csp.Should().Contain("connect-src 'self' https://tiles.openfreemap.org https://test-issuer.detour.invalid;");
        response.Headers.GetValues("X-Content-Type-Options").Single().Should().Be("nosniff");
        response.Headers.GetValues("Referrer-Policy").Single().Should().Be("no-referrer");
    }

    [Fact]
    public async Task Without_a_build_nothing_is_served()
    {
        var response = await _factory.CreateClient().GetAsync("/planner/");

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task The_api_still_answers_beside_the_planner()
    {
        var response = await Client().GetAsync("/api/capabilities");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }
}
