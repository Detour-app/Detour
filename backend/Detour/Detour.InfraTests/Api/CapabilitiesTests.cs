using System.Net;
using System.Net.Http.Json;
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

    private sealed record StubGateway(DevicePlatform Platform, bool Enabled) : IPushGateway
    {
        public Task<PushSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct) =>
            Task.FromResult(PushSendResult.Empty);
    }

    private sealed record CapabilitiesPayload(
        int Schema,
        IReadOnlyList<string> Features,
        IdpPayload Idp);

    private sealed record IdpPayload(string Issuer);
}
