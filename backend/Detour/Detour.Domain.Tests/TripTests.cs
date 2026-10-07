using Detour.Domain.Trips;

namespace Detour.Domain.Tests;

public class TripTests
{
    private static Trip Stored(long? editedAtMs) =>
        Trip.Create(Guid.NewGuid(), 1_000, "{}", new TripSummary(null, 0, 0, null, "CAR", editedAtMs)).Value;

    [Fact]
    public void A_newer_edit_replaces_an_older_one()
    {
        Assert.True(Stored(editedAtMs: 5_000).Accepts(6_000));
    }

    [Fact]
    public void An_unedited_copy_does_not_replace_an_edit()
    {
        // The #486 revert: a second device that never saw the edit re-uploads its old copy.
        Assert.False(Stored(editedAtMs: 5_000).Accepts(0));
    }

    [Fact]
    public void An_older_edit_does_not_replace_a_newer_one()
    {
        Assert.False(Stored(editedAtMs: 5_000).Accepts(4_000));
    }

    [Fact]
    public void A_re_upload_of_the_same_edit_replaces()
    {
        Assert.True(Stored(editedAtMs: 5_000).Accepts(5_000));
    }

    [Fact]
    public void A_copy_from_a_client_without_the_stamp_keeps_last_write_wins()
    {
        Assert.True(Stored(editedAtMs: 5_000).Accepts(null));
        Assert.True(Stored(editedAtMs: null).Accepts(0));
    }

    [Fact]
    public void Replace_carries_the_edit_stamp()
    {
        var trip = Stored(editedAtMs: 0);

        trip.Replace("{}", new TripSummary(null, 0, 0, null, "MOTO", 7_000));

        Assert.Equal(7_000, trip.EditedAtMs);
    }
}
