using System.Net.Http.Json;
using System.Text.Json;
using Detour.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// What a rider leaves in rows other riders own: a friendship on each side of the id order, a
/// route shared to a friend, and a position, place and presence event in someone else's circle.
/// Each of those references the account, so deleting it has to clear them (#700) — both the
/// rider's own delete and the administrator's go through the same cascade.
/// </summary>
internal static class AccountFootprint
{
    public sealed record Rider(HttpClient Client, string Username, Guid Id);

    /// <summary>
    /// <paramref name="earlier"/> and <paramref name="later"/> are signed up before and after the
    /// leaver, so one of the two friendships has the leaver on the high-id side.
    /// </summary>
    public static async Task Seed(Rider leaver, Rider earlier, Rider later)
    {
        await Befriend(earlier, leaver);
        await Befriend(leaver, later);

        (await leaver.Client.PostAsJsonAsync("/api/shared-routes", new
        {
            to = earlier.Username,
            route = new { id = 1L, name = "Coast road", stops = new[] { new { lat = 1.0 }, new { lat = 2.0 } } },
        })).EnsureSuccessStatusCode();

        var created = await earlier.Client.PostAsJsonAsync("/api/circles", new { name = "Household" });
        created.EnsureSuccessStatusCode();
        var circle = (await created.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
        (await earlier.Client.PostAsJsonAsync($"/api/groups/{circle}/invitations", new { username = leaver.Username }))
            .EnsureSuccessStatusCode();
        (await leaver.Client.PostAsJsonAsync($"/api/groups/{circle}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        (await leaver.Client.PostAsJsonAsync($"/api/circles/{circle}/positions",
                new { latitude = 51.0, longitude = 3.7, accuracyMeters = 12.0, timestampMs = 1_700_000_000_000L }))
            .EnsureSuccessStatusCode();
        (await leaver.Client.PostAsJsonAsync($"/api/circles/{circle}/places",
                new { place = new { id = 7L, name = "School", radiusMeters = 200.0, latitude = 51.0, longitude = 3.7 } }))
            .EnsureSuccessStatusCode();
        (await leaver.Client.PostAsJsonAsync($"/api/circles/{circle}/events",
                new { placeId = 7L, kind = "arrive", timestampMs = 1_700_000_000_000L }))
            .EnsureSuccessStatusCode();
    }

    /// <summary>
    /// Whether any row of the footprint still references <paramref name="riderId"/>, one entry
    /// per table so a failure names the table that kept the account.
    /// </summary>
    public static async Task<Dictionary<string, bool>> Remaining(DetourDbContext db, Guid riderId) => new()
    {
        ["friendships (low side)"] = await db.Friendships.AnyAsync(f => f.LowUserId == riderId),
        ["friendships (high side)"] = await db.Friendships.AnyAsync(f => f.HighUserId == riderId),
        ["shared_routes (sender)"] = await db.SharedRoutes.AnyAsync(r => r.FromUserId == riderId),
        ["member_fixes"] = await db.MemberFixes.AnyAsync(f => f.UserId == riderId),
        ["circle_places"] = await db.CirclePlaces.AnyAsync(p => p.OwnerId == riderId),
        ["place_events"] = await db.PlaceEvents.AnyAsync(e => e.UserId == riderId),
    };

    private static async Task Befriend(Rider from, Rider to)
    {
        (await from.Client.PostAsJsonAsync("/api/friends/requests", new { username = to.Username }))
            .EnsureSuccessStatusCode();
        (await to.Client.PostAsJsonAsync($"/api/friends/requests/{from.Id}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }
}
