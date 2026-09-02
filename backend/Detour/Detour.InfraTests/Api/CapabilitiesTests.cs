using System.Net;
using System.Net.Http.Json;
using Detour.InfraTests.Database;

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
    public async Task Capabilities_announce_the_idp_discovery_feature()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload!.Schema.Should().Be(1);
        payload.Features.Should().Contain("idp-discovery",
            "the app skips features it does not know, so the name is the contract");
    }

    private sealed record CapabilitiesPayload(
        int Schema,
        IReadOnlyList<string> Features,
        IdpPayload Idp);

    private sealed record IdpPayload(string Issuer);
}
