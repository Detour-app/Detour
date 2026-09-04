using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using Detour.Api.Notifications;
using Detour.Domain.Notifications;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;

namespace Detour.InfraTests.Api;

/// <summary>
/// Unit coverage for the platform-native push seam: the dispatcher routes each
/// token to the gateway for its platform, and each gateway classifies HTTP
/// responses into deliver / prune correctly. No DB, no live FCM/APNs — a stub
/// HttpMessageHandler stands in for the clouds, which is the whole point of the
/// native gateways over the FirebaseAdmin relay (its BatchResponse could not be
/// faked, so the prune path went untested).
/// </summary>
public class PushDispatcherTests
{
    private sealed class FakeGateway(DevicePlatform platform) : IPushGateway
    {
        public DevicePlatform Platform { get; } = platform;
        public List<string> SeenTokens { get; } = [];
        public PushSendResult NextResult { get; set; } = PushSendResult.Empty;

        public Task<PushSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            SeenTokens.AddRange(tokens);
            return Task.FromResult(NextResult);
        }
    }

    private static Mock<IDeviceTokenRepository> RepoReturning(params DeviceTokenTarget[] targets)
    {
        var repo = new Mock<IDeviceTokenRepository>();
        repo.Setup(r => r.GetForUsersAsync(It.IsAny<IReadOnlyCollection<Guid>>(), It.IsAny<CancellationToken>()))
            .ReturnsAsync(targets.ToList());
        return repo;
    }

    [Fact]
    public async Task Each_platforms_tokens_go_only_to_its_own_gateway()
    {
        var alex = Guid.NewGuid();
        var blake = Guid.NewGuid();
        var repo = RepoReturning(
            new DeviceTokenTarget(alex, "fcm-alex", DevicePlatform.Android),
            new DeviceTokenTarget(alex, "apns-alex", DevicePlatform.Ios),
            new DeviceTokenTarget(blake, "fcm-blake", DevicePlatform.Android));
        var fcm = new FakeGateway(DevicePlatform.Android);
        var apns = new FakeGateway(DevicePlatform.Ios);

        var dispatcher = new PushDispatcher(repo.Object, [fcm, apns], NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex, blake], "circle-1"), default);

        fcm.SeenTokens.Should().BeEquivalentTo(["fcm-alex", "fcm-blake"]);
        apns.SeenTokens.Should().BeEquivalentTo(["apns-alex"]);
    }

    [Fact]
    public async Task Dead_tokens_from_every_platform_are_pruned()
    {
        var alex = Guid.NewGuid();
        var repo = RepoReturning(
            new DeviceTokenTarget(alex, "fcm-dead", DevicePlatform.Android),
            new DeviceTokenTarget(alex, "apns-dead", DevicePlatform.Ios));
        var fcm = new FakeGateway(DevicePlatform.Android)
        {
            NextResult = new PushSendResult([new PushTokenOutcome("fcm-dead", false, ShouldPrune: true)]),
        };
        var apns = new FakeGateway(DevicePlatform.Ios)
        {
            NextResult = new PushSendResult([new PushTokenOutcome("apns-dead", false, ShouldPrune: true)]),
        };

        var dispatcher = new PushDispatcher(repo.Object, [fcm, apns], NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex], "circle-1"), default);

        repo.Verify(r => r.DeleteByTokensAsync(
            It.Is<IReadOnlyCollection<string>>(t => t.Contains("fcm-dead") && t.Contains("apns-dead")),
            It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task A_platform_with_no_gateway_is_skipped_not_fatal()
    {
        var alex = Guid.NewGuid();
        var repo = RepoReturning(
            new DeviceTokenTarget(alex, "fcm-alex", DevicePlatform.Android),
            new DeviceTokenTarget(alex, "apns-alex", DevicePlatform.Ios));
        var fcm = new FakeGateway(DevicePlatform.Android);

        // Only the Android gateway is registered; the iOS token has nowhere to go.
        var dispatcher = new PushDispatcher(repo.Object, [fcm], NullLogger<PushDispatcher>.Instance);
        var act = async () => await dispatcher.DispatchAsync(new PushJob([alex], "circle-1"), default);

        await act.Should().NotThrowAsync();
        fcm.SeenTokens.Should().BeEquivalentTo(["fcm-alex"]);
    }

    [Fact]
    public async Task No_tokens_means_no_gateway_call()
    {
        var repo = RepoReturning();
        var fcm = new FakeGateway(DevicePlatform.Android);

        var dispatcher = new PushDispatcher(repo.Object, [fcm], NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([Guid.NewGuid()], "circle-1"), default);

        fcm.SeenTokens.Should().BeEmpty();
    }
}

public class CachedJwtTests
{
    [Fact]
    public void Reuses_the_token_until_it_ages_past_the_ttl()
    {
        var now = DateTimeOffset.UtcNow;
        var mints = 0;
        var jwt = new CachedJwt(() => $"token-{++mints}", TimeSpan.FromMinutes(40), () => now);

        jwt.Current.Should().Be("token-1");
        jwt.Current.Should().Be("token-1"); // within ttl → same token, no re-mint
        mints.Should().Be(1);

        now = now.AddMinutes(41); // past ttl
        jwt.Current.Should().Be("token-2");
        mints.Should().Be(2);
    }
}

public class FcmGatewayTests
{
    private static FcmGateway Configured(StubHandler handler) =>
        new("project-123", _ => Task.FromResult("bearer-abc"),
            StubHandler.Factory(handler), NullLogger<FcmGateway>.Instance);

    [Fact]
    public async Task An_unconfigured_gateway_sends_nothing()
    {
        var gateway = new FcmGateway(
            projectId: null, accessToken: null,
            StubHandler.Factory(new StubHandler(_ => new(HttpStatusCode.OK))),
            NullLogger<FcmGateway>.Instance);

        var result = await gateway.SendWakeAsync(["fcm-a"], "circle-1", default);

        result.Outcomes.Should().BeEmpty();
    }

    [Fact]
    public async Task A_200_is_delivered_and_not_pruned()
    {
        var gateway = Configured(new StubHandler(_ => new(HttpStatusCode.OK)));

        var result = await gateway.SendWakeAsync(["fcm-live"], "circle-1", default);

        result.Outcomes.Should().ContainSingle()
            .Which.Should().BeEquivalentTo(new { Token = "fcm-live", Delivered = true, ShouldPrune = false });
    }

    [Fact]
    public async Task A_404_UNREGISTERED_prunes_the_token()
    {
        var gateway = Configured(new StubHandler(_ => Json(HttpStatusCode.NotFound,
            new { error = new { details = new[] { new { errorCode = "UNREGISTERED" } } } })));

        var result = await gateway.SendWakeAsync(["fcm-dead"], "circle-1", default);

        result.TokensToPrune.Should().ContainSingle().Which.Should().Be("fcm-dead");
    }

    [Fact]
    public async Task A_401_is_a_transient_fault_and_prunes_nothing()
    {
        var gateway = Configured(new StubHandler(_ => new(HttpStatusCode.Unauthorized)));

        var result = await gateway.SendWakeAsync(["fcm-live"], "circle-1", default);

        result.TokensToPrune.Should().BeEmpty();
    }

    private static HttpResponseMessage Json(HttpStatusCode status, object body) =>
        new(status)
        {
            Content = new StringContent(
                JsonSerializer.Serialize(body), System.Text.Encoding.UTF8, "application/json"),
        };
}

public class ApnsGatewayTests
{
    private static (NotificationSettings Settings, string KeyPath) ConfiguredSettings()
    {
        using var ec = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var path = Path.Combine(Path.GetTempPath(), $"apns-{Guid.NewGuid():N}.p8");
        File.WriteAllText(path, ec.ExportPkcs8PrivateKeyPem());
        return (new NotificationSettings
        {
            ApnsKeyPath = path,
            ApnsKeyId = "ABC123DEFG",
            ApnsTeamId = "TEAM123456",
            ApnsTopic = "io.github.maxke24.detour",
        }, path);
    }

    private static ApnsGateway Configured(StubHandler handler)
    {
        var (settings, _) = ConfiguredSettings();
        return new ApnsGateway(settings, StubHandler.Factory(handler), NullLogger<ApnsGateway>.Instance);
    }

    [Fact]
    public async Task An_unconfigured_gateway_sends_nothing()
    {
        var gateway = new ApnsGateway(
            new NotificationSettings { ApnsKeyPath = null },
            StubHandler.Factory(new StubHandler(_ => new(HttpStatusCode.OK))),
            NullLogger<ApnsGateway>.Instance);

        var result = await gateway.SendWakeAsync(["apns-a"], "circle-1", default);

        result.Outcomes.Should().BeEmpty();
    }

    [Fact]
    public async Task A_200_carries_a_signed_bearer_and_the_apns_headers()
    {
        var handler = new StubHandler(_ => new(HttpStatusCode.OK));
        var gateway = Configured(handler);

        var result = await gateway.SendWakeAsync(["apns-live"], "circle-42", default);

        result.Outcomes.Should().ContainSingle().Which.Delivered.Should().BeTrue();
        var request = handler.Requests.Single();
        request.Headers.GetValues("authorization").Single().Should().StartWith("bearer ");
        request.Headers.GetValues("apns-collapse-id").Single().Should().Be("circle-42");
        request.Headers.GetValues("apns-push-type").Single().Should().Be("alert");
    }

    [Fact]
    public async Task A_410_Unregistered_prunes_the_token()
    {
        var gateway = Configured(new StubHandler(_ => new(HttpStatusCode.Gone)));

        var result = await gateway.SendWakeAsync(["apns-dead"], "circle-1", default);

        result.TokensToPrune.Should().ContainSingle().Which.Should().Be("apns-dead");
    }

    [Fact]
    public async Task A_400_BadDeviceToken_prunes_the_token()
    {
        var gateway = Configured(new StubHandler(_ =>
            new(HttpStatusCode.BadRequest)
            {
                Content = new StringContent(
                    "{\"reason\":\"BadDeviceToken\"}", System.Text.Encoding.UTF8, "application/json"),
            }));

        var result = await gateway.SendWakeAsync(["apns-bad"], "circle-1", default);

        result.TokensToPrune.Should().ContainSingle().Which.Should().Be("apns-bad");
    }

    [Fact]
    public async Task A_429_is_transient_and_prunes_nothing()
    {
        var gateway = Configured(new StubHandler(_ => new(HttpStatusCode.TooManyRequests)));

        var result = await gateway.SendWakeAsync(["apns-live"], "circle-1", default);

        result.TokensToPrune.Should().BeEmpty();
    }
}

public class PushQueueTests
{
    [Fact]
    public void A_full_queue_drops_rather_than_blocks()
    {
        var queue = new PushQueue(
            new NotificationSettings { QueueCapacity = 2 }, NullLogger<PushQueue>.Instance);

        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c1")).Should().BeTrue();
        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c2")).Should().BeTrue();
        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c3")).Should().BeFalse();
    }
}

/// <summary>A one-response HTTP handler and a matching IHttpClientFactory, so a
///  gateway can be pointed at a canned cloud reply.</summary>
internal sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
{
    public List<HttpRequestMessage> Requests { get; } = [];

    protected override Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Requests.Add(request);
        return Task.FromResult(responder(request));
    }

    public static IHttpClientFactory Factory(StubHandler handler)
    {
        var factory = new Mock<IHttpClientFactory>();
        factory.Setup(f => f.CreateClient(It.IsAny<string>())).Returns(() => new HttpClient(handler));
        return factory.Object;
    }
}
