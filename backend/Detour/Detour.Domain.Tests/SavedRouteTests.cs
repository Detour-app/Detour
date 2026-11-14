using JV.ResultUtilities;
using Detour.Domain.Routes;

namespace Detour.Domain.Tests;

public class SavedRouteTests
{
    private static SavedRoute Stored(long editedAtMs) =>
        SavedRoute.Create(Guid.NewGuid(), 7, "{\"name\":\"stored\"}", editedAtMs).Value;

    [Fact]
    public void A_newer_stamp_replaces_the_stored_copy()
    {
        var stored = Stored(5_000);

        Assert.True(stored.ReplaceIfNewer("{\"name\":\"newer\"}", 6_000));
        Assert.Equal("{\"name\":\"newer\"}", stored.Payload);
        Assert.Equal(6_000, stored.EditedAtMs);
    }

    [Fact]
    public void An_older_stamp_does_not_revert_a_newer_edit()
    {
        // #909: a phone that never saw the planner's edit re-uploads the copy it held before.
        var stored = Stored(5_000);

        Assert.False(stored.ReplaceIfNewer("{\"name\":\"older\"}", 4_000));
        Assert.Equal("{\"name\":\"stored\"}", stored.Payload);
        Assert.Equal(5_000, stored.EditedAtMs);
    }

    [Fact]
    public void An_unstamped_upload_cannot_replace_a_stamped_copy()
    {
        var stored = Stored(5_000);

        Assert.False(stored.ReplaceIfNewer("{\"name\":\"old app\"}", 0));
        Assert.Equal("{\"name\":\"stored\"}", stored.Payload);
    }

    [Fact]
    public void Two_unstamped_copies_keep_last_upload_wins()
    {
        // Today's rule for an app that predates stamps: a rename on the phone still propagates.
        var stored = Stored(0);

        Assert.True(stored.ReplaceIfNewer("{\"name\":\"renamed\"}", 0));
        Assert.Equal("{\"name\":\"renamed\"}", stored.Payload);
    }

    [Fact]
    public void An_equal_stamp_replaces()
    {
        var stored = Stored(5_000);

        Assert.True(stored.ReplaceIfNewer("{\"name\":\"same edit\"}", 5_000));
        Assert.Equal("{\"name\":\"same edit\"}", stored.Payload);
    }

    [Fact]
    public void A_negative_stamp_counts_as_none()
    {
        Assert.Equal(0, Stored(-1).EditedAtMs);
        Assert.False(Stored(5_000).ReplaceIfNewer("{\"name\":\"x\"}", -1));
    }

    [Fact]
    public void An_empty_payload_never_replaces()
    {
        var stored = Stored(5_000);

        Assert.False(stored.ReplaceIfNewer(" ", 6_000));
        Assert.Equal(5_000, stored.EditedAtMs);
    }

    [Fact]
    public void A_tombstone_needs_an_id()
    {
        Assert.True(SavedRouteTombstone.Create(Guid.NewGuid(), 0).IsFailure);
        Assert.False(SavedRouteTombstone.Create(Guid.NewGuid(), 7).IsFailure);
    }

    // --- planner edits (#912) --------------------------------------------------------------

    private static Result Edit(
        long documentId = 7,
        long editedAtMs = 5_000,
        int stopCount = 2,
        string? mode = "MOTO",
        string payload = "{\"id\":7}") =>
        SavedRoute.ValidateEdit(7, documentId, editedAtMs, stopCount, mode, payload);

    [Fact]
    public void A_complete_planner_edit_passes()
    {
        Assert.False(Edit().IsFailure);
    }

    [Fact]
    public void A_mode_the_app_does_not_know_still_passes()
    {
        // The app reads it as a car, and a later app may add the mode.
        Assert.False(Edit(mode: "HOVERCRAFT").IsFailure);
    }

    [Fact]
    public void A_document_whose_id_differs_from_the_path_is_refused()
    {
        Assert.True(Edit(documentId: 8).HasError(ValidationKeys.SavedRoute.IdMismatch));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void A_planner_edit_needs_a_stamp(long editedAtMs)
    {
        Assert.True(Edit(editedAtMs: editedAtMs).HasError(ValidationKeys.SavedRoute.EditStampRequired));
    }

    [Fact]
    public void A_route_with_one_stop_is_refused()
    {
        Assert.True(Edit(stopCount: 1).HasError(ValidationKeys.SavedRoute.NotEnoughStops));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("  ")]
    public void A_route_without_a_mode_is_refused(string? mode)
    {
        Assert.True(Edit(mode: mode).HasError(ValidationKeys.SavedRoute.ModeRequired));
    }

    [Fact]
    public void An_oversized_route_is_refused()
    {
        var payload = new string('x', DetourLimits.MaxRoutePayloadBytes + 1);

        Assert.True(Edit(payload: payload).HasError(ValidationKeys.SavedRoute.PayloadTooLarge));
    }
}
