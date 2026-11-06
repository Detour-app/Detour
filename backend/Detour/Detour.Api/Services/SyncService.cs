using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Places;
using Detour.Domain.Routes;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using JV.ResultUtilities;
using JV.ResultUtilities.ValidationMessage;

namespace Detour.Api.Services;

public interface ISyncService
{
    Task<Result<SyncResponse>> MergeAsync(User user, SyncRequest request, CancellationToken cancellationToken);
}

/// <summary>
/// The bidirectional merge, and the one place the sync rules live.
///
/// Idempotent by construction: every write is keyed on something the device owns — a trip's
/// start instant, a trace's content, a place's client id — so the same input twice produces the
/// same state.
/// </summary>
public class SyncService(
    ITripRepository trips,
    ITripTombstoneRepository tripTombstones,
    ITraceRepository traces,
    ITraceTombstoneRepository traceTombstones,
    ITrackPointRepository trackPoints,
    ISavedPlaceRepository savedPlaces,
    ISavedRouteRepository savedRoutes,
    IBadgeAwardRepository badges,
    ITripTitleRepository tripTitles,
    ILogger<SyncService> logger) : ISyncService
{
    private static readonly JsonSerializerOptions PayloadOptions = new(JsonSerializerDefaults.Web);

    public async Task<Result<SyncResponse>> MergeAsync(
        User user,
        SyncRequest request,
        CancellationToken cancellationToken)
    {
        // Validate everything before writing anything. One malformed entry has to fail the whole
        // sync rather than leave a partial import committed — a device that retries would then
        // be merging against a state neither side believes in.
        var validation = Validate(request);
        if (validation.IsFailure)
            return validation;

        var rowWindows = await MergeTripsAsync(user.Id, request, cancellationToken);
        await MergeTracesAsync(user.Id, request.Traces, cancellationToken);
        await RemoveDeletedRidesTracesAsync(user.Id, rowWindows, ReportedWindows(request), cancellationToken);
        await MergeSavedPlacesAsync(user.Id, request, cancellationToken);
        await MergeSavedRoutesAsync(user.Id, request, cancellationToken);
        await MergeBadgesAsync(user.Id, request.Badges, cancellationToken);
        await MergeTripTitlesAsync(user.Id, request.TripTitles, cancellationToken);

        // Absent means "no update", not "clear".
        if (request.Stats is { } stats)
            user.ReplaceStats(ToDomain(stats));

        if (request.ShareFog is { } shareFog)
            user.SetFogSharing(shareFog);

        return await BuildResponseAsync(user, cancellationToken);
    }

    private static Result Validate(SyncRequest request)
    {
        var messages = new List<ValidationMessage>();

        foreach (var trip in request.Trips ?? [])
        {
            if (trip.StartTimeMs <= 0)
                messages.Add(ValidationMessage.Create(ValidationKeys.Trip.StartTimeRequired));
        }

        foreach (var title in request.TripTitles ?? [])
        {
            if (title.StartTimeMs <= 0)
                messages.Add(ValidationMessage.Create(ValidationKeys.Trip.StartTimeRequired));
        }

        foreach (var place in request.SavedPlaces ?? [])
        {
            if (place.Id == 0)
                messages.Add(ValidationMessage.Create(ValidationKeys.SavedPlace.IdRequired));
        }

        foreach (var route in request.SavedRoutes ?? [])
        {
            if (route.Id == 0)
                messages.Add(ValidationMessage.Create(ValidationKeys.SavedRoute.IdRequired));
        }

        foreach (var line in request.Traces ?? [])
        {
            if (string.IsNullOrWhiteSpace(line))
                messages.Add(ValidationMessage.Create(ValidationKeys.Trace.LineRequired));
            else if (!TraceLineReader.IsWellFormed(line))
                messages.Add(ValidationMessage.Create(ValidationKeys.Trace.LineInvalid));
        }

        return messages.Count > 0 ? Result.Create(messages) : Result.Ok();
    }

    private static IReadOnlyList<TimeWindow> ReportedWindows(SyncRequest request) =>
        [.. (request.DeletedTripWindows ?? [])
            .Where(w => w.StartTimeMs > 0 && IsPlausibleRide(w.StartTimeMs, w.EndTimeMs))
            .Select(w => new TimeWindow(w.StartTimeMs, w.EndTimeMs))
            .Distinct()];

    /// <summary>Whether a deleted ride's span may be removed: see <see cref="DetourLimits.MaxDeletedRideWindow"/>.</summary>
    private static bool IsPlausibleRide(long startMs, long endMs) =>
        endMs >= startMs && endMs - startMs <= (long)DetourLimits.MaxDeletedRideWindow.TotalMilliseconds;

    /// <summary>Returns the time window of each stored ride this request deleted, for its trace to go too.</summary>
    private async Task<IReadOnlyList<TimeWindow>> MergeTripsAsync(
        Guid userId,
        SyncRequest request,
        CancellationToken cancellationToken)
    {
        // A ride deleted on any device — earlier, or in this very request — is never taken back
        // from an upload. The device uploading it has not heard of the deletion yet; it hears of
        // it in the response instead (#778).
        var tombstoned = (await tripTombstones.GetStartsForUserAsync(userId, cancellationToken)).ToHashSet();
        var deleted = (request.DeletedTripStartTimes ?? [])
            .Concat((request.DeletedTripWindows ?? []).Select(w => w.StartTimeMs))
            .Where(s => s > 0)
            .Distinct()
            .ToArray();

        var incoming = (request.Trips ?? [])
            .Where(t => !tombstoned.Contains(t.StartTimeMs) && !deleted.Contains(t.StartTimeMs))
            .ToList();
        if (incoming.Count > 0)
        {
            var starts = incoming.Select(t => t.StartTimeMs).Distinct().ToArray();
            var existing = (await trips.GetByStartsAsync(userId, starts, cancellationToken))
                .ToDictionary(t => t.StartTimeMs);

            foreach (var payload in incoming)
            {
                var document = JsonSerializer.Serialize(payload, PayloadOptions);
                var summary = new TripSummary(
                    payload.EndTimeMs,
                    payload.DistanceMeters,
                    payload.TopSpeedKmh,
                    payload.MaxGForce,
                    payload.Mode,
                    payload.EditedAtMs);

                if (existing.TryGetValue(payload.StartTimeMs, out var stored))
                {
                    // Replace, not ignore: a trip re-uploaded with an edit — a corrected vehicle
                    // mode, a trimmed end — must overwrite, or the stale row comes back in the
                    // merge below and reverts the edit on the device that made it. Unless the
                    // stored copy holds a newer edit: then this is another device's stale copy,
                    // and replacing would revert that edit instead (#486).
                    if (stored.Accepts(payload.EditedAtMs))
                        stored.Replace(document, summary);
                    continue;
                }

                var (result, trip) = Trip.Create(userId, payload.StartTimeMs, document, summary);
                if (result.IsFailure)
                {
                    // Validate() already rejected the only failure this can have. Log rather
                    // than throw so one odd trip cannot fail a sync that passed validation.
                    logger.LogWarning(
                        "Skipping trip {Start} for {UserId}: {Errors}",
                        payload.StartTimeMs, userId,
                        string.Join("; ", result.ValidationMessages.Select(m => m.TranslationKey)));
                    continue;
                }

                await trips.SaveAsync(trip, cancellationToken);
                existing[payload.StartTimeMs] = trip;
            }
        }

        if (deleted.Length == 0)
            return [];

        // Read before the row goes: a trace line carries no trip reference, so the row's window
        // is the only thing that says which points were this ride's. A ride with no recorded end
        // or an implausible span has no window, and keeps its points.
        var removed = await trips.GetByStartsAsync(userId, deleted, cancellationToken);
        var windows = removed
            .Where(t => t.EndTimeMs is { } end && IsPlausibleRide(t.StartTimeMs, end))
            .Select(t => new TimeWindow(t.StartTimeMs, t.EndTimeMs!.Value))
            .ToList();

        // The row goes, and a tombstone stays in its place. A hard delete alone let the next
        // device still holding the ride upload it again, so the server copy flipped between
        // deleted and present on every sync (#778).
        await trips.DeleteByStartsAsync(userId, deleted, cancellationToken);
        foreach (var start in deleted.Where(s => !tombstoned.Contains(s)))
        {
            var (result, tombstone) = TripTombstone.Create(userId, start);
            if (result.IsFailure)
                continue; // filtered above; a non-positive start has no ride to bury

            await tripTombstones.SaveAsync(tombstone, cancellationToken);
        }

        return windows;
    }

    /// <summary>
    /// Takes a deleted ride's location off the server (#780): its samples, and every line with a
    /// point in its window. A line is a flush of the tracker's buffer rather than a ride, so one
    /// can straddle the window; the points either side stay, as new lines.
    ///
    /// Runs after <see cref="MergeTracesAsync"/> so this request's own lines are covered too: an
    /// older client deletes a ride without cutting its trace, and uploads the line alongside.
    ///
    /// <paramref name="rowWindows"/> come from rows this request deleted, so they apply once.
    /// <paramref name="reportedWindows"/> come back on every sync (#877), so each only applies
    /// while a stored sample sits inside it: an indexed lookup rather than re-parsing the whole
    /// history per sync. Looking at the data rather than at the trip tombstone also covers a line
    /// that reaches the server after the deletion, and a deletion an older server already buried
    /// without its window.
    /// </summary>
    private async Task RemoveDeletedRidesTracesAsync(
        Guid userId,
        IReadOnlyList<TimeWindow> rowWindows,
        IReadOnlyList<TimeWindow> reportedWindows,
        CancellationToken cancellationToken)
    {
        if (rowWindows.Count == 0 && reportedWindows.Count == 0)
            return;

        // The lookups and bulk deletes below go straight to the database, past anything still pending.
        await traces.FlushChangesAsync(cancellationToken);

        var windows = rowWindows.ToList();
        foreach (var window in reportedWindows)
        {
            if (await trackPoints.AnyInWindowAsync(userId, window.FromMs, window.ToMs, cancellationToken))
                windows.Add(window);
        }

        if (windows.Count == 0)
            return;

        foreach (var window in windows)
            await trackPoints.DeleteInWindowAsync(userId, window.FromMs, window.ToMs, cancellationToken);

        var stored = await traces.GetForUserAsync(userId, cancellationToken);
        var cutHashes = new List<string>();
        var survivors = new Dictionary<string, string>();
        foreach (var trace in stored)
        {
            if (TraceLineReader.WithoutWindows(trace.Line, windows) is not { } runs)
                continue;

            cutHashes.Add(trace.LineHash);
            foreach (var run in runs)
                survivors[Trace.HashOf(run)] = run;
        }

        if (cutHashes.Count == 0)
            return;

        await traces.DeleteByHashesAsync(userId, cutHashes, cancellationToken);

        foreach (var hash in cutHashes)
        {
            var (result, tombstone) = TraceTombstone.Create(userId, hash);
            if (result.IsFailure)
                continue; // a stored line always has a hash

            await traceTombstones.SaveAsync(tombstone, cancellationToken);
        }

        // The deleting device's own cut lines arrived in this request, so most survivors are
        // already held. Their samples are too: they sit outside the window, so none were removed.
        var held = stored.Select(t => t.LineHash).ToHashSet();
        var buried = await traceTombstones.GetExistingHashesAsync(userId, survivors.Keys, cancellationToken);
        foreach (var (hash, run) in survivors)
        {
            if (held.Contains(hash) || buried.Contains(hash))
                continue;

            var (result, trace) = Trace.Create(userId, run);
            if (result.IsFailure)
                continue; // a run holds at least two points, so it is never blank

            await traces.SaveAsync(trace, cancellationToken);
        }
    }

    private async Task MergeTracesAsync(
        Guid userId,
        IReadOnlyList<string>? incoming,
        CancellationToken cancellationToken)
    {
        if (incoming is not { Count: > 0 })
            return;

        var byHash = new Dictionary<string, string>();
        foreach (var raw in incoming)
        {
            var line = raw.Trim();
            byHash[Trace.HashOf(line)] = line;
        }

        // One round trip to find out which lines are already held. Every sync re-sends the whole
        // history, so asking per line would make the hot path O(history) queries.
        var known = await traces.GetExistingHashesAsync(userId, [.. byHash.Keys], cancellationToken);

        // A line removed with a deleted ride is not new, however many devices still hold it (#780).
        known.UnionWith(await traceTombstones.GetExistingHashesAsync(userId, [.. byHash.Keys], cancellationToken));

        // Points are gathered across every new line and added once, rather than per line.
        // Consecutive lines deliberately share their boundary sample: the device flushes its
        // buffer mid-drive and re-emits the last point so the drawn trace has no break, and a
        // point's key is (owner, instant). Adding per line handed the change tracker two
        // instances of one key, which threw and rolled the whole sync back — so a first full
        // sync failed on every flush boundary the history contained.
        var fresh = new Dictionary<long, TrackPoint>();

        foreach (var (hash, line) in byHash)
        {
            if (known.Contains(hash))
                continue;

            var (result, trace) = Trace.Create(userId, line);
            if (result.IsFailure)
                continue;

            await traces.SaveAsync(trace, cancellationToken);

            // Only a genuinely new line has points worth unpacking. Doing it on the
            // already-held path would re-parse the entire history on every sync.
            //
            // First writer wins on a shared instant. The two copies are the same sample, so
            // which one is kept does not matter — only that one is.
            foreach (var point in TraceLineReader.ReadPoints(userId, line))
                fresh.TryAdd(point.TimestampMs, point);
        }

        if (fresh.Count == 0)
            return;

        // The other half of the same overlap: when the line that ended on the shared instant
        // synced earlier, the hash check above skips it, so its stored sample is invisible here
        // and inserting the instant again violates the key at commit rather than in the tracker.
        var stored = await trackPoints.GetExistingTimestampsAsync(
            userId,
            fresh.Keys.Min(),
            fresh.Keys.Max(),
            cancellationToken);

        var unseen = fresh.Values.Where(p => !stored.Contains(p.TimestampMs)).ToArray();
        if (unseen.Length > 0)
            await trackPoints.AddRangeAsync(unseen, cancellationToken);
    }

    private async Task MergeSavedPlacesAsync(
        Guid userId,
        SyncRequest request,
        CancellationToken cancellationToken)
    {
        await UpsertSavedPlacesAsync(userId, request.SavedPlaces, cancellationToken);

        // After the upserts, as for trips: a place added and removed between two syncs is sent
        // in both lists and has to end up gone (#695).
        var deleted = request.DeletedSavedPlaceIds ?? [];
        if (deleted.Count > 0)
        {
            await savedPlaces.FlushChangesAsync(cancellationToken);
            await savedPlaces.DeleteByClientIdsAsync(userId, [.. deleted.Distinct()], cancellationToken);
        }
    }

    private async Task UpsertSavedPlacesAsync(
        Guid userId,
        IReadOnlyList<SavedPlacePayload>? incoming,
        CancellationToken cancellationToken)
    {
        if (incoming is not { Count: > 0 })
            return;

        var ids = incoming.Select(p => p.Id).Distinct().ToArray();
        var existing = (await savedPlaces.GetByClientIdsAsync(userId, ids, cancellationToken))
            .ToDictionary(p => p.ClientPlaceId);

        foreach (var payload in incoming)
        {
            var document = JsonSerializer.Serialize(payload, PayloadOptions);

            if (existing.TryGetValue(payload.Id, out var stored))
            {
                stored.Replace(document);
                continue;
            }

            var (result, place) = SavedPlace.Create(userId, payload.Id, document);
            if (result.IsFailure)
                continue;

            await savedPlaces.SaveAsync(place, cancellationToken);
            existing[payload.Id] = place;
        }
    }

    private async Task MergeSavedRoutesAsync(
        Guid userId,
        SyncRequest request,
        CancellationToken cancellationToken)
    {
        await UpsertSavedRoutesAsync(userId, request.SavedRoutes, cancellationToken);

        // After the upserts, as for saved places: a route saved and deleted between two syncs is
        // sent in both lists and has to end up gone.
        var deleted = request.DeletedSavedRouteIds ?? [];
        if (deleted.Count > 0)
        {
            await savedRoutes.FlushChangesAsync(cancellationToken);
            await savedRoutes.DeleteByClientIdsAsync(userId, [.. deleted.Distinct()], cancellationToken);
        }
    }

    private async Task UpsertSavedRoutesAsync(
        Guid userId,
        IReadOnlyList<SavedRoutePayload>? incoming,
        CancellationToken cancellationToken)
    {
        if (incoming is not { Count: > 0 })
            return;

        var ids = incoming.Select(r => r.Id).Distinct().ToArray();
        var existing = (await savedRoutes.GetByClientIdsAsync(userId, ids, cancellationToken))
            .ToDictionary(r => r.ClientRouteId);

        foreach (var payload in incoming)
        {
            var document = JsonSerializer.Serialize(payload, PayloadOptions);

            // Last upload wins, as for saved places: a route carries no edit stamp to compare.
            if (existing.TryGetValue(payload.Id, out var stored))
            {
                stored.Replace(document);
                continue;
            }

            var (result, route) = SavedRoute.Create(userId, payload.Id, document);
            if (result.IsFailure)
                continue;

            await savedRoutes.SaveAsync(route, cancellationToken);
            existing[payload.Id] = route;
        }
    }

    private async Task MergeBadgesAsync(
        Guid userId,
        IReadOnlyDictionary<string, long>? incoming,
        CancellationToken cancellationToken)
    {
        if (incoming is not { Count: > 0 })
            return;

        var existing = (await badges.GetForUserAsync(userId, cancellationToken))
            .ToDictionary(b => b.BadgeId);

        // Capped, so a client cannot grow this table without bound. Take the earliest instants
        // rather than an arbitrary slice: those are the ones the rule below would keep anyway.
        var candidates = incoming
            .OrderBy(pair => pair.Value)
            .Take(DetourLimits.MaxBadgesPerUser);

        foreach (var (badgeId, earnedAtMs) in candidates)
        {
            if (existing.TryGetValue(badgeId, out var award))
            {
                // First time earned wins, so a reinstall cannot move the date forward.
                award.KeepEarliest(earnedAtMs);
                continue;
            }

            if (existing.Count >= DetourLimits.MaxBadgesPerUser)
                break;

            var (result, created) = BadgeAward.Create(userId, badgeId, earnedAtMs);
            if (result.IsFailure)
                continue; // an unrecognisable badge id is dropped, not a failed sync

            await badges.SaveAsync(created, cancellationToken);
            existing[badgeId] = created;
        }
    }

    private async Task MergeTripTitlesAsync(
        Guid userId,
        IReadOnlyList<TripTitlePayload>? incoming,
        CancellationToken cancellationToken)
    {
        if (incoming is not { Count: > 0 })
            return;

        var existing = (await tripTitles.GetForUserAsync(userId, cancellationToken))
            .ToDictionary(t => t.TripStartTimeMs);

        foreach (var payload in incoming)
        {
            if (existing.TryGetValue(payload.StartTimeMs, out var stored))
            {
                stored.KeepNewest(payload.Title, payload.EditedAtMs);
                continue;
            }

            var (result, created) = TripTitle.Create(userId, payload.StartTimeMs, payload.Title, payload.EditedAtMs);
            if (result.IsFailure)
                continue; // an over-long title is dropped, not a failed sync

            await tripTitles.SaveAsync(created, cancellationToken);
            existing[payload.StartTimeMs] = created;
        }
    }

    private async Task<SyncResponse> BuildResponseAsync(User user, CancellationToken cancellationToken)
    {
        // Flush first: the union below has to include what this request just wrote, and the
        // transaction middleware does not commit until the response is on its way out.
        await trips.FlushChangesAsync(cancellationToken);

        var storedTrips = await trips.GetForUserAsync(user.Id, cancellationToken);
        var storedTombstones = await tripTombstones.GetStartsForUserAsync(user.Id, cancellationToken);
        var storedTraces = await traces.GetForUserAsync(user.Id, cancellationToken);
        var storedPlaces = await savedPlaces.GetForUserAsync(user.Id, cancellationToken);
        var storedRoutes = await savedRoutes.GetForUserAsync(user.Id, cancellationToken);
        var storedBadges = await badges.GetForUserAsync(user.Id, cancellationToken);
        var storedTitles = await tripTitles.GetForUserAsync(user.Id, cancellationToken);

        return new SyncResponse(
            [.. storedTrips.Select(t => JsonSerializer.Deserialize<JsonElement>(t.Payload))],
            storedTombstones,
            [.. storedTraces.Select(t => t.Line)],
            [.. storedPlaces.Select(p => JsonSerializer.Deserialize<JsonElement>(p.Payload))],
            [.. storedRoutes.Select(r => JsonSerializer.Deserialize<JsonElement>(r.Payload))],
            storedBadges.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs),
            [.. storedTitles.Select(t => new TripTitlePayload(t.TripStartTimeMs, t.Title, t.EditedAtMs))],
            user.ShareFog);
    }

    private static RiderStats ToDomain(RiderStatsPayload payload) => RiderStats.Sanitize(
        new RiderStats(
            payload.TotalDistanceMeters,
            payload.TopSpeedKmh,
            payload.LongestTripMeters,
            payload.MaxLeanDegrees,
            payload.MunicipalitiesVisited,
            payload.BestCoveragePercent,
            payload.TripCount));
}
