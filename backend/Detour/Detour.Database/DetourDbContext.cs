using Detour.Domain.ApiKeys;
using Detour.Domain.Circles;
using Detour.Domain.Friendships;
using Detour.Domain.Groups;
using Detour.Domain.Notifications;
using Detour.Domain.Places;
using Detour.Domain.Routes;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;

namespace Detour.Database;

public class DetourDbContext(DbContextOptions<DetourDbContext> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<BadgeAward> BadgeAwards => Set<BadgeAward>();
    public DbSet<Trip> Trips => Set<Trip>();
    public DbSet<Trace> Traces => Set<Trace>();
    public DbSet<TrackPoint> TrackPoints => Set<TrackPoint>();
    public DbSet<SavedPlace> SavedPlaces => Set<SavedPlace>();
    public DbSet<Friendship> Friendships => Set<Friendship>();
    public DbSet<SharedRoute> SharedRoutes => Set<SharedRoute>();
    public DbSet<Group> Groups => Set<Group>();
    public DbSet<GroupMember> GroupMembers => Set<GroupMember>();
    public DbSet<MemberFix> MemberFixes => Set<MemberFix>();
    public DbSet<CirclePlace> CirclePlaces => Set<CirclePlace>();
    public DbSet<PlaceEvent> PlaceEvents => Set<PlaceEvent>();
    public DbSet<ApiKey> ApiKeys => Set<ApiKey>();
    public DbSet<DeviceToken> DeviceTokens => Set<DeviceToken>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.HasDefaultSchema(DetourDatabaseConventions.Schema);

        // The citext extension is registered automatically by Npgsql because two columns use
        // that type — see UserConfiguration for why they do.

        modelBuilder.ApplyConfigurationsFromAssembly(typeof(DetourDbContext).Assembly);

        // Every entity id is application-generated (Guid.CreateVersion7 in the constructor).
        // Without this, EF infers ValueGeneratedOnAdd for Guid keys and entities added through
        // a navigation collection are tracked as Unchanged instead of Added — they silently
        // never get inserted.
        foreach (var entityType in modelBuilder.Model.GetEntityTypes())
        {
            var primaryKey = entityType.FindPrimaryKey();
            if (primaryKey is null)
                continue;

            foreach (var property in primaryKey.Properties.Where(p => p.ClrType == typeof(Guid)))
                property.ValueGenerated = ValueGenerated.Never;
        }
    }

    protected override void OnConfiguring(DbContextOptionsBuilder optionsBuilder)
    {
        optionsBuilder.UseSnakeCaseNamingConvention();
    }
}
