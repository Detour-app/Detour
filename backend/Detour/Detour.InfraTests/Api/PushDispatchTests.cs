using Detour.Api.Notifications;
using Detour.Domain.Notifications;
using Microsoft.Extensions.Logging.Abstractions;

namespace Detour.InfraTests.Api;

public class FcmGatewayTests
{
    [Fact]
    public async Task An_unconfigured_gateway_sends_nothing_and_prunes_nothing()
    {
        var gateway = new FcmGateway(
            new NotificationSettings { FirebaseCredentialsPath = null },
            NullLogger<FcmGateway>.Instance);

        var result = await gateway.SendWakeAsync(["fcm-a", "fcm-b"], "circle-1", default);

        result.Outcomes.Should().BeEmpty();
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

public class PushDispatcherTests
{
    private sealed class FakeGateway : IFcmGateway
    {
        public List<string> SeenTokens { get; } = [];
        public FcmSendResult NextResult { get; set; } = FcmSendResult.Empty;

        public Task<FcmSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            SeenTokens.AddRange(tokens);
            return Task.FromResult(NextResult);
        }
    }

    private sealed class FakeTokenRepo : IDeviceTokenRepository
    {
        public Dictionary<Guid, List<string>> ByUser { get; } = [];
        public List<string> Deleted { get; } = [];

        public Task<List<DeviceTokenTarget>> GetForUsersAsync(
            IReadOnlyCollection<Guid> userIds, CancellationToken ct) =>
            Task.FromResult(userIds
                .SelectMany(u => ByUser.GetValueOrDefault(u, []).Select(t => new DeviceTokenTarget(u, t)))
                .ToList());

        public Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken ct)
        {
            Deleted.AddRange(tokens);
            return Task.CompletedTask;
        }

        // The rest of IDeviceTokenRepository / IBaseRepository<DeviceToken> throw
        // NotImplementedException — the dispatcher only calls the two above.
        public Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken ct) => throw new NotImplementedException();
        public Task<DeviceToken?> GetAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<DeviceToken?> GetNonTrackingAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<bool> ExistsAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<List<DeviceToken>> GetAllAsync(CancellationToken token) => throw new NotImplementedException();
        public Task<List<DeviceToken>> GetAllNonTrackingAsync(CancellationToken token) => throw new NotImplementedException();
        public Task SaveAsync(DeviceToken entity, CancellationToken token) => throw new NotImplementedException();
        public void Save(DeviceToken entity) => throw new NotImplementedException();
        public void Delete(DeviceToken entity) => throw new NotImplementedException();
        public Task ReloadAsync(DeviceToken entity, CancellationToken token) => throw new NotImplementedException();
        public Task FlushChangesAsync(CancellationToken token) => throw new NotImplementedException();
    }

    [Fact]
    public async Task Dispatch_sends_to_every_token_of_every_recipient()
    {
        var alex = Guid.NewGuid();
        var blake = Guid.NewGuid();
        var gateway = new FakeGateway();
        var repo = new FakeTokenRepo();
        repo.ByUser[alex] = ["fcm-alex-1", "fcm-alex-2"];
        repo.ByUser[blake] = ["fcm-blake"];

        var dispatcher = new PushDispatcher(repo, gateway, NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex, blake], "circle-1"), default);

        gateway.SeenTokens.Should().BeEquivalentTo(["fcm-alex-1", "fcm-alex-2", "fcm-blake"]);
    }

    [Fact]
    public async Task Dispatch_prunes_the_tokens_the_gateway_reports_dead()
    {
        var alex = Guid.NewGuid();
        var gateway = new FakeGateway();
        var repo = new FakeTokenRepo();
        repo.ByUser[alex] = ["fcm-live", "fcm-dead"];
        gateway.NextResult = new FcmSendResult(
        [
            new FcmTokenOutcome("fcm-live", Delivered: true, ShouldPrune: false),
            new FcmTokenOutcome("fcm-dead", Delivered: false, ShouldPrune: true),
        ]);

        var dispatcher = new PushDispatcher(repo, gateway, NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex], "circle-1"), default);

        repo.Deleted.Should().ContainSingle().Which.Should().Be("fcm-dead");
    }

    [Fact]
    public async Task Dispatch_with_no_tokens_never_calls_the_gateway()
    {
        var gateway = new FakeGateway();
        var dispatcher = new PushDispatcher(new FakeTokenRepo(), gateway, NullLogger<PushDispatcher>.Instance);

        await dispatcher.DispatchAsync(new PushJob([Guid.NewGuid()], "circle-1"), default);

        gateway.SeenTokens.Should().BeEmpty();
    }
}
