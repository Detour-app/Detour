using Detour.Domain.Groups;
using Detour.Domain.Traces;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace Detour.InfraTests.Database;

/// <summary>
/// The schema-level promises. Every one of these is something the InMemory provider would let
/// through and real Postgres would not — which is exactly why they are worth a container.
/// </summary>
[Collection(PostgresCollection.Name)]
public class SchemaTests(PostgresFixture fixture)
{
    [Fact]
    public async Task The_migration_leaves_no_pending_model_changes()
    {
        await using var db = fixture.CreateContext();

        var pending = await db.Database.GetPendingMigrationsAsync();

        pending.Should().BeEmpty("the checked-in migration must match the model");
    }

    [Fact]
    public async Task Handles_are_unique_case_insensitively()
    {
        // citext, not a plain unique index: without it "Rider" and "rider" are two accounts and
        // a friend request goes to whichever one the query happened to match.
        await using var db = fixture.CreateContext();
        var (_, first) = User.Create($"subject-{Guid.NewGuid()}", "CaseRider", null);
        db.Users.Add(first);
        await db.SaveChangesAsync();

        var (_, second) = User.Create($"subject-{Guid.NewGuid()}", "caserider", null);
        db.Users.Add(second);

        var act = async () => await db.SaveChangesAsync();

        (await act.Should().ThrowAsync<DbUpdateException>())
            .WithInnerExceptionExactly<PostgresException>()
            .Which.SqlState.Should().Be(PostgresErrorCodes.UniqueViolation);
    }

    [Fact]
    public async Task A_handle_lookup_ignores_case()
    {
        await using var db = fixture.CreateContext();
        var (_, user) = User.Create($"subject-{Guid.NewGuid()}", "MixedCase", null);
        db.Users.Add(user);
        await db.SaveChangesAsync();

        var found = await db.Users.FirstOrDefaultAsync(u => u.Username == "mixedcase");

        found.Should().NotBeNull();
    }

    [Fact]
    public async Task One_trace_line_per_owner_survives_a_re_upload()
    {
        await using var db = fixture.CreateContext();
        var user = await SeedUserAsync(db);
        const string line = "[[51.05,3.72,1000,50.0,12.5]]";

        var (_, first) = Trace.Create(user.Id, line);
        db.Traces.Add(first);
        await db.SaveChangesAsync();

        var (_, duplicate) = Trace.Create(user.Id, line);
        db.Traces.Add(duplicate);

        var act = async () => await db.SaveChangesAsync();

        (await act.Should().ThrowAsync<DbUpdateException>())
            .WithInnerExceptionExactly<PostgresException>()
            .Which.SqlState.Should().Be(PostgresErrorCodes.UniqueViolation);
    }

    [Fact]
    public async Task Deleting_an_account_takes_every_row_it_owns_with_it()
    {
        await using var db = fixture.CreateContext();
        var user = await SeedUserAsync(db);

        var (_, badge) = BadgeAward.Create(user.Id, "dist_100000", 1_000);
        db.BadgeAwards.Add(badge);
        var (_, trace) = Trace.Create(user.Id, $"[[1,2,{Random.Shared.Next()}]]");
        db.Traces.Add(trace);
        var point = TrackPoint.TryCreate(user.Id, 1_000, 51.05, 3.72, 40, 10)!;
        db.TrackPoints.Add(point);
        await db.SaveChangesAsync();

        db.Users.Remove(user);
        await db.SaveChangesAsync();

        (await db.BadgeAwards.AnyAsync(b => b.UserId == user.Id)).Should().BeFalse();
        (await db.Traces.AnyAsync(t => t.UserId == user.Id)).Should().BeFalse();
        (await db.TrackPoints.AnyAsync(p => p.UserId == user.Id)).Should().BeFalse();
    }

    [Fact]
    public async Task A_group_kind_round_trips_as_its_name()
    {
        // Stored by name, not by ordinal: reordering the members must never silently remap
        // every existing row, and a dump has to be readable.
        await using var db = fixture.CreateContext();
        var owner = await SeedUserAsync(db);
        var (_, circle) = Group.Create(GroupKind.Circle, "Household", owner.Id);
        db.Groups.Add(circle);
        await db.SaveChangesAsync();

        var stored = await db.Database
            .SqlQuery<string>($"SELECT kind AS \"Value\" FROM detour.groups WHERE id = {circle.Id}")
            .SingleAsync();

        stored.Should().Be("Circle");
    }

    [Fact]
    public async Task Creating_a_group_persists_its_owner_membership()
    {
        // The owner row is added through the aggregate's navigation collection, which is the
        // exact case the ValueGeneratedNever override in OnModelCreating exists to fix.
        await using var db = fixture.CreateContext();
        var owner = await SeedUserAsync(db);
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Sunday ride", owner.Id);
        db.Groups.Add(convoy);
        await db.SaveChangesAsync();

        var members = await db.GroupMembers.Where(m => m.GroupId == convoy.Id).ToListAsync();

        members.Should().ContainSingle();
        members[0].UserId.Should().Be(owner.Id);
        members[0].IsSharing.Should().BeTrue();
    }

    private static async Task<User> SeedUserAsync(Detour.Database.DetourDbContext db)
    {
        var suffix = Guid.NewGuid().ToString("N")[..8];
        var (_, user) = User.Create($"subject-{suffix}", $"rider{suffix}", null);
        db.Users.Add(user);
        await db.SaveChangesAsync();
        return user;
    }
}
