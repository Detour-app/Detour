using Detour.Domain;
using Detour.Domain.Notifications;

namespace Detour.Domain.Tests.Notifications;

public class DeviceTokenTests
{
    [Fact]
    public void Create_rejects_a_blank_token()
    {
        var result = DeviceToken.Create(Guid.CreateVersion7(), "  ", DevicePlatform.Android);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.DeviceToken.TokenRequired).Should().BeTrue();
    }

    [Fact]
    public void Create_rejects_an_oversized_token()
    {
        var result = DeviceToken.Create(
            Guid.CreateVersion7(), new string('t', 513), DevicePlatform.Android);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.DeviceToken.TokenInvalid).Should().BeTrue();
    }

    [Fact]
    public void Create_rejects_an_unknown_platform()
    {
        var result = DeviceToken.Create(Guid.CreateVersion7(), "fcm-abc", platform: null);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.DeviceToken.PlatformInvalid).Should().BeTrue();
    }

    [Fact]
    public void Refresh_reassigns_the_owner_and_bumps_the_timestamp()
    {
        var original = Guid.CreateVersion7();
        var token = DeviceToken.Create(original, "fcm-abc", DevicePlatform.Android).Value;
        var firstSeen = token.LastRefreshedAt;
        var newOwner = Guid.CreateVersion7();

        token.Refresh(newOwner, DevicePlatform.Ios);

        token.UserId.Should().Be(newOwner);
        token.Platform.Should().Be(DevicePlatform.Ios);
        token.LastRefreshedAt.Should().BeOnOrAfter(firstSeen);
    }
}
