using System.Net;
using System.Net.Http.Json;
using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Detour.Api.Notifications;
using Detour.Domain.Notifications;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class CapabilitiesTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Capabilities_are_reachable_without_a_token()
    {
        var response = await _factory.CreateClient().GetAsync("/api/capabilities");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Capabilities_state_the_configured_authority_verbatim()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        // Exactly, not merely a prefix: the app pins this value and the token
        // pipeline compares `iss` against it with string equality.
        payload!.Idp.Issuer.Should().Be(DetourApiFactory.Issuer);
    }

    [Fact]
    public async Task Capabilities_state_the_current_schema_version()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Schema.Should().Be(1);
    }

    [Fact]
    public async Task Capabilities_announce_the_idp_discovery_feature()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Features.Should().Contain("idp-discovery",
            "the app skips features it does not know, so the name is the contract");
    }

    [Fact]
    public async Task Capabilities_do_not_announce_push_when_no_cloud_is_configured()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        // This deployment has neither a Firebase key nor an APNs one, which is the
        // correct state for a self-hoster who has been handed no credentials. An
        // Android client reads this and keeps its own relay running rather than
        // standing it down for a wake-ping that would never be sent.
        payload!.Features.Should().NotContain("push-android");
        payload.Features.Should().NotContain("push-ios");
    }

    [Fact]
    public async Task Capabilities_announce_only_the_platform_whose_gateway_is_configured()
    {
        using var web = _factory.WithWebHostBuilder(builder =>
            builder.ConfigureTestServices(services =>
            {
                services.RemoveAll<IPushGateway>();
                services.AddSingleton<IPushGateway>(new StubGateway(DevicePlatform.Android, Enabled: true));
                services.AddSingleton<IPushGateway>(new StubGateway(DevicePlatform.Ios, Enabled: false));
            }));

        var payload = await web.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Features.Should().Contain("push-android");
        // Per-platform, not one "push" flag: a deployment routinely has Firebase
        // credentials and no APNs key, and an iOS client must not read Android's.
        payload.Features.Should().NotContain("push-ios");
    }

    [Fact]
    public async Task Capabilities_do_not_announce_routing_or_geocoder_when_unconfigured()
    {
        // The default deployment — this factory sets neither `Routing:BaseUrl`
        // nor `Geocoder:BaseUrl` — matches every server running today, which is
        // why this needs no coordination with them (#177).
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Routing.Should().BeNull();
        payload.Geocoder.Should().BeNull();
        payload.Features.Should().NotContain("routing-discovery");
        payload.Features.Should().NotContain("geocoder-discovery");
    }

    [Fact]
    public async Task Capabilities_announce_a_configured_routing_and_geocoder_base_url()
    {
        using var web = _factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Routing:BaseUrl", "https://gh.example.invalid");
            builder.UseSetting("Geocoder:BaseUrl", "https://photon.example.invalid");
        });

        var payload = await web.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Routing.Should().NotBeNull();
        payload.Routing!.BaseUrl.Should().Be("https://gh.example.invalid");
        payload.Geocoder.Should().NotBeNull();
        payload.Geocoder!.BaseUrl.Should().Be("https://photon.example.invalid");
        payload.Features.Should().Contain("routing-discovery");
        payload.Features.Should().Contain("geocoder-discovery");
    }

    [Fact]
    public async Task Capabilities_announce_routing_independently_of_geocoder()
    {
        // A self-hoster routinely runs one and not the other — GraphHopper and
        // Photon are separate processes with separate reasons to exist.
        using var web = _factory.WithWebHostBuilder(builder =>
            builder.UseSetting("Routing:BaseUrl", "https://gh.example.invalid"));

        var payload = await web.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Routing.Should().NotBeNull();
        payload.Geocoder.Should().BeNull();
        payload.Features.Should().Contain("routing-discovery");
        payload.Features.Should().NotContain("geocoder-discovery");
    }

    [Fact]
    public void FeaturesFor_includes_cameras_discovery_when_a_camera_base_is_announced()
    {
        var features = CapabilitiesResponse.FeaturesFor(
            [], routingAnnounced: false, geocoderAnnounced: false, camerasAnnounced: true, speedLimitsAnnounced: false);

        Assert.Contains(CapabilitiesResponse.CamerasDiscoveryFeature, features);
    }

    [Fact]
    public void FeaturesFor_includes_speedlimits_discovery_when_a_speedlimits_base_is_announced()
    {
        var features = CapabilitiesResponse.FeaturesFor(
            [], routingAnnounced: false, geocoderAnnounced: false, camerasAnnounced: false, speedLimitsAnnounced: true);

        Assert.Contains(CapabilitiesResponse.SpeedLimitsDiscoveryFeature, features);
    }

    [Fact]
    public void From_omits_the_cameras_block_when_unconfigured()
    {
        var response = CapabilitiesResponse.From(
            new IdpSettings { Authority = "http://x", Audience = "a" },
            [], new RoutingSettings(), new GeocoderSettings(), new CameraSettings(), new SpeedLimitSettings());

        Assert.Null(response.Cameras);
    }

    [Fact]
    public void From_omits_the_speedlimits_block_when_unconfigured()
    {
        var response = CapabilitiesResponse.From(
            new IdpSettings { Authority = "http://x", Audience = "a" },
            [], new RoutingSettings(), new GeocoderSettings(), new CameraSettings(), new SpeedLimitSettings());

        Assert.Null(response.SpeedLimits);
    }

    [Fact]
    public async Task Capabilities_do_not_announce_cameras_when_unconfigured()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Cameras.Should().BeNull();
        payload.Features.Should().NotContain("cameras-discovery");
    }

    [Fact]
    public async Task Capabilities_announce_a_configured_cameras_base_url()
    {
        using var web = _factory.WithWebHostBuilder(builder =>
            builder.UseSetting("Camera:BaseUrl", "https://cameras.example.invalid"));

        var payload = await web.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.Cameras.Should().NotBeNull();
        payload.Cameras!.BaseUrl.Should().Be("https://cameras.example.invalid");
        payload.Features.Should().Contain("cameras-discovery");
    }

    [Fact]
    public async Task Capabilities_do_not_announce_speedlimits_when_unconfigured()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.SpeedLimits.Should().BeNull();
        payload.Features.Should().NotContain("speedlimits-discovery");
    }

    [Fact]
    public async Task Capabilities_announce_a_configured_speedlimits_base_url()
    {
        using var web = _factory.WithWebHostBuilder(builder =>
            builder.UseSetting("SpeedLimit:BaseUrl", "https://speedlimits.example.invalid"));

        var payload = await web.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        payload!.SpeedLimits.Should().NotBeNull();
        payload.SpeedLimits!.BaseUrl.Should().Be("https://speedlimits.example.invalid");
        payload.Features.Should().Contain("speedlimits-discovery");
    }

    private sealed record StubGateway(DevicePlatform Platform, bool Enabled) : IPushGateway
    {
        public Task<PushSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct) =>
            Task.FromResult(PushSendResult.Empty);
    }

    private sealed record CapabilitiesPayload(
        int Schema,
        IReadOnlyList<string> Features,
        IdpPayload Idp,
        RoutingPayload? Routing,
        GeocoderPayload? Geocoder,
        CameraPayload? Cameras,
        SpeedLimitPayload? SpeedLimits);

    private sealed record IdpPayload(string Issuer);

    private sealed record RoutingPayload(string BaseUrl);

    private sealed record GeocoderPayload(string BaseUrl);

    private sealed record CameraPayload(string BaseUrl);

    private sealed record SpeedLimitPayload(string BaseUrl);
}
