using Detour.Database.Repositories;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using Detour.InfraTests.Database;

namespace Detour.InfraTests;

[Collection(PostgresCollection.Name)]
public class GroupRepositoryTests(PostgresFixture postgres) : IntegrationTestBase(postgres)
{
    [Fact]
    public async Task GetAcceptedMembershipsAsync_returns_only_accepted_rows_of_the_asked_riders()
    {
        // The live revocation sweep's single query: an invite is not a membership, and a rider
        // who was not asked about must not come back.
        await using var db = postgres.CreateContext();
        var owner = await SeedUserAsync(db);
        var invitee = await SeedUserAsync(db);
        var stranger = await SeedUserAsync(db);
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Sunday ride", owner.Id);
        convoy.Invite(invitee.Id);
        var (_, other) = Group.Create(GroupKind.Convoy, "Other ride", stranger.Id);
        db.Groups.AddRange(convoy, other);
        await db.SaveChangesAsync();

        var memberships = await new GroupRepository(Factory)
            .GetAcceptedMembershipsAsync([owner.Id, invitee.Id], CancellationToken.None);

        memberships.Select(m => (m.UserId, m.GroupId)).Should().Equal((owner.Id, convoy.Id));
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
