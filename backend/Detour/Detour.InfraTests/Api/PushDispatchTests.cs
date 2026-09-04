using Detour.Api.Notifications;
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
