using Detour.Domain;
using Detour.Domain.Friendships;
using Detour.Domain.Routes;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database;
using Shared.Database.Converters;

namespace Detour.Database.EntityConfigurations;

public class FriendshipConfiguration : IEntityTypeConfiguration<Friendship>
{
    public void Configure(EntityTypeBuilder<Friendship> builder)
    {
        builder.ToTable("friendships");

        builder.HasKey(f => f.Id);
        builder.Property(f => f.Id).ValueGeneratedNever();

        builder.Property(f => f.Status)
            .HasConversion<SmartEnumNameConverter<FriendshipStatus>>()
            .HasMaxLength(20);

        builder.Property(f => f.CreatedAt);
        builder.Property(f => f.AcceptedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(f => f.LowUserId)
            .OnDelete(DeleteBehavior.Cascade);

        // NoAction on the second leg: two cascade paths into the same table make PostgreSQL
        // refuse the constraint. Deleting a rider still removes both sides, because the
        // low-id leg above cascades and the account-deletion path clears the rest explicitly.
        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(f => f.HighUserId)
            .OnDelete(DeleteBehavior.NoAction);

        // Ids are stored in a fixed order, so one row per pair is a unique index rather than a
        // rule the handlers have to remember.
        builder.HasIndex(f => new { f.LowUserId, f.HighUserId }).IsUnique();
        builder.HasIndex(f => f.HighUserId);
    }
}

public class SharedRouteConfiguration : IEntityTypeConfiguration<SharedRoute>
{
    public void Configure(EntityTypeBuilder<SharedRoute> builder)
    {
        builder.ToTable("shared_routes");

        builder.HasKey(r => r.Id);
        builder.Property(r => r.Id).ValueGeneratedNever();

        builder.Property(r => r.Name).HasMaxLength(DetourLimits.DisplayNameMaxLength);
        builder.Property(r => r.Payload).HasColumnType(ColumnTypes.Jsonb);
        builder.Property(r => r.ClientRouteId);
        builder.Property(r => r.CreatedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(r => r.ToUserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(r => r.FromUserId)
            .OnDelete(DeleteBehavior.NoAction);

        // Re-sharing an edited route replaces the recipient's copy. The sender is part of the
        // key too, so two friends sharing routes that happen to carry the same client-side id
        // do not collide with each other.
        builder.HasIndex(r => new { r.ToUserId, r.FromUserId, r.ClientRouteId }).IsUnique();

        // The inbox read: newest first for one recipient.
        builder.HasIndex(r => new { r.ToUserId, r.CreatedAt });
    }
}
