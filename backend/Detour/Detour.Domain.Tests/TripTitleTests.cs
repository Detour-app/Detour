using Detour.Domain.Trips;

namespace Detour.Domain.Tests;

public class TripTitleTests
{
    private static TripTitle Stored(string title, long editedAtMs) =>
        TripTitle.Create(Guid.NewGuid(), 1_000, title, editedAtMs).Value;

    [Fact]
    public void A_newer_rename_replaces_an_older_one()
    {
        var stored = Stored("Coast road", 5_000);

        Assert.True(stored.KeepNewest("Coast road, again", 6_000));
        Assert.Equal("Coast road, again", stored.Title);
        Assert.Equal(6_000, stored.EditedAtMs);
    }

    [Fact]
    public void An_older_rename_does_not_revert_a_newer_one()
    {
        // The #471 revert: a second device re-uploads the title it held before the rename.
        var stored = Stored("Coast road", 5_000);

        Assert.False(stored.KeepNewest("Sunday ride", 4_000));
        Assert.Equal("Coast road", stored.Title);
    }

    [Fact]
    public void A_re_upload_of_the_same_edit_changes_nothing()
    {
        Assert.False(Stored("Coast road", 5_000).KeepNewest("Coast road", 5_000));
    }

    [Fact]
    public void A_cleared_title_is_a_tombstone_an_older_title_cannot_overwrite()
    {
        var stored = Stored("Coast road", 5_000);

        Assert.True(stored.KeepNewest("", 6_000));
        Assert.Equal("", stored.Title);
        Assert.False(stored.KeepNewest("Coast road", 5_000));
        Assert.Equal("", stored.Title);
    }

    [Fact]
    public void A_title_is_stored_trimmed()
    {
        Assert.Equal("Coast road", Stored("  Coast road ", 5_000).Title);
    }

    [Fact]
    public void An_over_long_title_is_refused()
    {
        var tooLong = new string('a', DetourLimits.DisplayNameMaxLength + 1);

        Assert.True(TripTitle.Create(Guid.NewGuid(), 1_000, tooLong, 5_000).IsFailure);
        Assert.False(Stored("Coast road", 5_000).KeepNewest(tooLong, 6_000));
    }

    [Fact]
    public void A_title_without_a_ride_start_is_refused()
    {
        Assert.True(TripTitle.Create(Guid.NewGuid(), 0, "Coast road", 5_000).IsFailure);
    }
}
