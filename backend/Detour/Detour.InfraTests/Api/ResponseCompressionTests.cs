using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// #699: a sync response is the rider's whole history, and a LAN or direct self-host has no
/// edge in front of it to compress that. The app asks for gzip; the server has to answer in it.
/// </summary>
[Collection(PostgresCollection.Name)]
public class ResponseCompressionTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task A_sync_response_is_gzipped_when_the_client_accepts_it()
    {
        var client = NewRider();
        client.DefaultRequestHeaders.AcceptEncoding.Add(new StringWithQualityHeaderValue("gzip"));

        var response = await client.PostAsJsonAsync("/api/sync", new { savedPlaces = new[] { new { id = 7L, name = "Home" } } });

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.Headers.ContentEncoding.Should().Equal("gzip");

        await using var gzip = new GZipStream(await response.Content.ReadAsStreamAsync(), CompressionMode.Decompress);
        var body = await JsonSerializer.DeserializeAsync<JsonElement>(gzip);
        body.GetProperty("savedPlaces").GetArrayLength().Should().Be(1);
    }

    [Fact]
    public async Task A_client_that_does_not_ask_for_gzip_gets_plain_json()
    {
        // An older client or a script with no Accept-Encoding must still read the body as is.
        var client = NewRider();

        var response = await client.PostAsJsonAsync("/api/sync", new { });

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.Headers.ContentEncoding.Should().BeEmpty();
        (await response.Content.ReadFromJsonAsync<JsonElement>()).ValueKind.Should().Be(JsonValueKind.Object);
    }

    private HttpClient NewRider() =>
        _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}",
            $"rider{Guid.NewGuid():N}"[..16],
            null,
            "detour-user"));
}
