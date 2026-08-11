using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Trips;

/// <summary>
/// One recorded journey.
///
/// The payload is stored opaquely and never parsed by this backend beyond the fields the
/// read-only dashboard needs. That is a privacy property, not laziness: the service cannot
/// disclose what it does not read, and it is worth keeping deliberately rather than losing to
/// a fully normalised schema.
///
/// Identity is (owner, start instant): a device re-uploading a trip it has edited must replace
/// the stored copy, or the stale row comes back in the next merge and reverts the edit.
/// </summary>
public sealed class Trip : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>Unix milliseconds. The natural key, together with <see cref="UserId"/>.</summary>
    public long StartTimeMs { get; private set; }

    /// <summary>Unix milliseconds, or null for a trip that never recorded an end.</summary>
    public long? EndTimeMs { get; private set; }

    /// <summary>Denormalised from the payload purely so the dashboard can list rides cheaply.</summary>
    public double DistanceMeters { get; private set; }

    public double TopSpeedKmh { get; private set; }

    public double? MaxGForce { get; private set; }

    public string? Mode { get; private set; }

    /// <summary>The client's own trip document, verbatim.</summary>
    public string Payload { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private Trip(Guid userId, long startTimeMs, string payload)
    {
        UserId = userId;
        StartTimeMs = startTimeMs;
        Payload = payload;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<Trip> Create(Guid userId, long startTimeMs, string payload, TripSummary summary)
    {
        var validation = Validate(startTimeMs, payload);
        if (validation.IsFailure)
            return validation;

        var trip = new Trip(userId, startTimeMs, payload);
        trip.ApplySummary(summary);
        return trip;
    }

    /// <summary>
    /// Replaces the stored copy in place. An edit — a corrected vehicle mode, a trimmed end —
    /// has to propagate, so this is an update and not an ignore.
    /// </summary>
    public Result Replace(string payload, TripSummary summary)
    {
        var validation = Validate(StartTimeMs, payload);
        if (validation.IsFailure)
            return validation;

        Payload = payload;
        ApplySummary(summary);
        UpdatedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    private void ApplySummary(TripSummary summary)
    {
        EndTimeMs = summary.EndTimeMs > StartTimeMs ? summary.EndTimeMs : null;
        DistanceMeters = double.IsFinite(summary.DistanceMeters) ? Math.Max(summary.DistanceMeters, 0) : 0;
        TopSpeedKmh = double.IsFinite(summary.TopSpeedKmh) ? Math.Max(summary.TopSpeedKmh, 0) : 0;
        MaxGForce = summary.MaxGForce is { } g && double.IsFinite(g) ? g : null;
        Mode = string.IsNullOrWhiteSpace(summary.Mode)
            ? null
            : summary.Mode.Trim()[..Math.Min(summary.Mode.Trim().Length, 32)];
    }

    private static Result Validate(long startTimeMs, string? payload)
    {
        if (startTimeMs <= 0)
            return Result.Error(ValidationKeys.Trip.StartTimeRequired);

        return string.IsNullOrWhiteSpace(payload)
            ? Result.Error(ValidationKeys.Trip.PayloadRequired)
            : Result.Ok();
    }
}

/// <summary>
/// The handful of fields lifted out of a trip payload so the dashboard can list and rank rides
/// without every read parsing every document.
/// </summary>
public readonly record struct TripSummary(
    long? EndTimeMs,
    double DistanceMeters,
    double TopSpeedKmh,
    double? MaxGForce,
    string? Mode);
