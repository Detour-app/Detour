using System.Net;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Hosting;

namespace Detour.InfraTests.Api;

/// <summary>
/// The web half of convoy invite links (#764): what Android reads to verify the App Link, and the
/// page a link opens on without the app.
/// </summary>
[Collection(PostgresCollection.Name)]
public class AppLinksTests(PostgresFixture postgres) : IAsyncLifetime
{
    private const string Release =
        "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5";

    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_unconfigured_deployment_publishes_an_empty_asset_links_file()
    {
        var response = await _factory.CreateClient().GetAsync("/.well-known/assetlinks.json");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.Headers.ContentType!.MediaType.Should().Be("application/json");
        (await response.Content.ReadAsStringAsync()).Should().Be("[]");
    }

    [Fact]
    public async Task Configured_apps_are_published_in_the_shape_android_verifies()
    {
        await using var configured = _factory.WithWebHostBuilder(b => b
            .UseSetting("AppLinks:Android:0:Package", "io.github.maxke24.detour")
            .UseSetting("AppLinks:Android:0:Sha256", Release.ToLowerInvariant())
            .UseSetting("AppLinks:Android:1:Package", "io.github.maxke24.detour.debug")
            .UseSetting("AppLinks:Android:1:Sha256", ""));

        var response = await configured.CreateClient().GetAsync("/.well-known/assetlinks.json");

        // Without a token: Android's verifier never sends one.
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        // The debug entry has no fingerprint, so it is left out rather than published empty.
        var statement = doc.RootElement.EnumerateArray().Should().ContainSingle().Subject;
        statement.GetProperty("relation")[0].GetString()
            .Should().Be("delegate_permission/common.handle_all_urls");
        var target = statement.GetProperty("target");
        target.GetProperty("namespace").GetString().Should().Be("android_app");
        target.GetProperty("package_name").GetString().Should().Be("io.github.maxke24.detour");
        // Upper case, as keytool prints it and as Android compares it.
        target.GetProperty("sha256_cert_fingerprints").EnumerateArray().Select(f => f.GetString())
            .Should().Equal(Release);
    }

    [Fact]
    public async Task Several_fingerprints_for_one_app_are_all_published()
    {
        const string rotated =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
        await using var configured = _factory.WithWebHostBuilder(b => b
            .UseSetting("AppLinks:Android:0:Package", "io.github.maxke24.detour")
            .UseSetting("AppLinks:Android:0:Sha256", $"{Release}, {rotated}"));

        var body = await configured.CreateClient().GetStringAsync("/.well-known/assetlinks.json");

        using var doc = JsonDocument.Parse(body);
        doc.RootElement[0].GetProperty("target").GetProperty("sha256_cert_fingerprints")
            .EnumerateArray().Select(f => f.GetString())
            .Should().Equal(Release, rotated);
    }

    [Fact]
    public async Task The_invite_page_opens_without_a_token_and_links_to_the_install_url()
    {
        await using var configured = _factory.WithWebHostBuilder(b => b
            .UseSetting("AppLinks:InstallUrl", "https://example.org/install?from=invite&x=\"1\""));

        var response = await configured.CreateClient().GetAsync("/convoy-invite");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.Headers.ContentType!.MediaType.Should().Be("text/html");
        var html = await response.Content.ReadAsStringAsync();
        // Encoded, so a self-hoster's URL can't break out of the attribute.
        html.Should().Contain("href=\"https://example.org/install?from=invite&amp;x=&quot;1&quot;\"");
    }
}
