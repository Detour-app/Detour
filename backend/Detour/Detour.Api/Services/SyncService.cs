using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Places;
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
    ITraceRepository traces,
    ITrackPointRepository trackPoints,
    ISavedPlaceRepository savedPlaces,
    IBadgeAwardRepository badges,
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

        await MergeTripsAsync(user.Id, request, cancellationToken);
        await MergeTracesAsync(user.Id, request.Traces, cancellationToken);
        await MergeSavedPlacesAsync(user.Id, request.SavedPlaces, cancellationToken);
        await MergeBadgesAsync(user.Id, request.Badges, cancellationToken);

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

        foreach (var place in request.SavedPlaces ?? [])
        {
            if (place.Id == 0)
                messages.Add(ValidationMessage.Create(ValidationKeys.SavedPlace.IdRequired));
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

    private async Task MergeTripsAsync(Guid userId, SyncRequest request, CancellationToken cancellationToken)
    {
        var incoming = request.Trips ?? [];
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
                    payload.Mode);

                if (existing.TryGetValue(payload.StartTimeMs, out var stored))
                {
                    // Replace, not ignore: a trip re-uploaded with an edit — a corrected vehicle
                    // mode, a trimmed end — must overwrite, or the stale row comes back in the
                    // merge below and reverts the edit on the device that made it.
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

        // After the upserts, so a trip in both lists ends up deleted.
        var deleted = request.DeletedTripStartTimes ?? [];
        if (deleted.Count > 0)
        {
            await trips.FlushChangesAsync(cancellationToken);
            await trips.DeleteByStartsAsync(userId, [.. deleted.Distinct()], cancellationToken);
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
            var points = TraceLineReader.ReadPoints(userId, line);
            if (points.Count > 0)
                await trackPoints.AddRangeAsync(points, cancellationToken);
        }
    }

    private async Task MergeSavedPlacesAsync(
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

    private async Task<SyncResponse> BuildResponseAsync(User user, CancellationToken cancellationToken)
    {
        // Flush first: the union below has to include what this request just wrote, and the
        // transaction middleware does not commit until the response is on its way out.
        await trips.FlushChangesAsync(cancellationToken);

        var storedTrips = await trips.GetForUserAsync(user.Id, cancellationToken);
        var storedTraces = await traces.GetForUserAsync(user.Id, cancellationToken);
        var storedPlaces = await savedPlaces.GetForUserAsync(user.Id, cancellationToken);
        var storedBadges = await badges.GetForUserAsync(user.Id, cancellationToken);

        return new SyncResponse(
            [.. storedTrips.Select(t => JsonSerializer.Deserialize<JsonElement>(t.Payload))],
            [.. storedTraces.Select(t => t.Line)],
            [.. storedPlaces.Select(p => JsonSerializer.Deserialize<JsonElement>(p.Payload))],
            storedBadges.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs),
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
