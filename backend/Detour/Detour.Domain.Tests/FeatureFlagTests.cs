using Detour.Domain.FeatureFlags;

namespace Detour.Domain.Tests;

public class FeatureFlagTests
{
    [Theory]
    [InlineData("flags-selftest")]
    [InlineData("a")]
    [InlineData("route-v2")]
    public void A_lowercase_hyphenated_key_is_a_flag(string key)
    {
        var created = FeatureFlag.Create(key, enabled: false);

        created.IsFailure.Should().BeFalse();
        Assert.Equal(key, created.Value.Key);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("Flags-Selftest")]
    [InlineData("flags_selftest")]
    [InlineData("flags selftest")]
    [InlineData("flags-selftest\n")]
    public void Anything_else_is_refused(string? key)
    {
        Assert.True(FeatureFlag.Create(key, enabled: false).IsFailure);
    }

    [Fact]
    public void A_key_is_at_most_64_characters()
    {
        Assert.False(FeatureFlag.Create(new string('a', 64), enabled: false).IsFailure);
        Assert.True(FeatureFlag.Create(new string('a', 65), enabled: false).IsFailure);
    }

    [Fact]
    public void An_override_names_its_flag_by_key()
    {
        var flag = FeatureFlag.Create("flags-selftest", enabled: false).Value;
        var userId = Guid.NewGuid();

        var over = flag.OverrideFor(userId, enabled: true);

        Assert.Equal("flags-selftest", over.FlagKey);
        Assert.Equal(userId, over.UserId);
        Assert.True(over.Enabled);
    }

    [Fact]
    public void Switching_a_default_moves_its_updated_time()
    {
        var flag = FeatureFlag.Create("flags-selftest", enabled: false).Value;
        var before = flag.UpdatedAt;

        flag.SetEnabled(true);

        Assert.True(flag.Enabled);
        Assert.True(flag.UpdatedAt >= before);
    }

    [Fact]
    public void An_override_can_be_switched()
    {
        var over = FeatureFlag.Create("flags-selftest", enabled: false).Value
            .OverrideFor(Guid.NewGuid(), enabled: true);

        over.SetEnabled(false);

        Assert.False(over.Enabled);
    }

    [Theory]
    [InlineData("flags-selftest", false)]
    [InlineData("Flags-Selftest", true)]
    [InlineData("", true)]
    public void A_key_is_validated_without_creating_a_flag(string key, bool failure)
    {
        Assert.Equal(failure, FeatureFlag.ValidateKey(key).IsFailure);
    }
}
