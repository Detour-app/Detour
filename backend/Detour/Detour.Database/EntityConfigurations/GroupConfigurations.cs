using Detour.Domain;
using Detour.Domain.Circles;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database;
using Shared.Database.Converters;

namespace Detour.Database.EntityConfigurations;

public class GroupConfiguration : IEntityTypeConfiguration<Group>
{
    public void Configure(EntityTypeBuilder<Group> builder)
    {
        builder.ToTable("groups");

        builder.HasKey(g => g.Id);
        builder.Property(g => g.Id).ValueGeneratedNever();

        builder.Property(g => g.Kind)
            .HasConversion<SmartEnumNameConverter<GroupKind>>()
            .HasMaxLength(20);

        builder.Property(g => g.Name).HasMaxLength(DetourLimits.GroupNameMaxLength);
        builder.Property(g => g.CreatedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(g => g.OwnerId)
            .OnDelete(DeleteBehavior.Cascade);

        // The aggregate owns its members: loading a group without them would let a handler
        // decide membership from a partially-loaded object.
        builder.HasMany(g => g.Members)
            .WithOne()
            .HasForeignKey(m => m.GroupId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.Navigation(g => g.Members)
            .UsePropertyAccessMode(PropertyAccessMode.Field)
            .HasField("_members");

        builder.HasIndex(g => new { g.Kind, g.OwnerId });
    }
}

public class GroupMemberConfiguration : IEntityTypeConfiguration<GroupMember>
{
    public void Configure(EntityTypeBuilder<GroupMember> builder)
    {
        builder.ToTable("group_members");

        builder.HasKey(m => m.Id);
        builder.Property(m => m.Id).ValueGeneratedNever();

        builder.Property(m => m.Status)
            .HasConversion<SmartEnumNameConverter<GroupMemberStatus>>()
            .HasMaxLength(20);

        builder.Property(m => m.JoinedAt);

        // Convoy rows leave this true and the relay ignores it for them; a circle's pause
        // switch is the only thing that clears it.
        builder.Property(m => m.IsSharing).HasDefaultValue(true);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(m => m.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(m => new { m.GroupId, m.UserId }).IsUnique();

        // "Which groups am I in" — the list endpoint's only query.
        builder.HasIndex(m => m.UserId);
    }
}

public class MemberFixConfiguration : IEntityTypeConfiguration<MemberFix>
{
    public void Configure(EntityTypeBuilder<MemberFix> builder)
    {
        builder.ToTable("member_fixes");

        builder.HasKey(f => f.Id);
        builder.Property(f => f.Id).ValueGeneratedNever();

        builder.Property(f => f.Latitude);
        builder.Property(f => f.Longitude);
        builder.Property(f => f.AccuracyMeters);
        builder.Property(f => f.TimestampMs);

        builder.HasOne<Group>()
            .WithMany()
            .HasForeignKey(f => f.GroupId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(f => f.UserId)
            .OnDelete(DeleteBehavior.NoAction);

        // One row per member per circle, overwritten in place. The unique index is what makes
        // "no history, no trail" a property of the schema rather than of the write path.
        builder.HasIndex(f => new { f.GroupId, f.UserId }).IsUnique();
    }
}

public class CirclePlaceConfiguration : IEntityTypeConfiguration<CirclePlace>
{
    public void Configure(EntityTypeBuilder<CirclePlace> builder)
    {
        builder.ToTable("circle_places");

        builder.HasKey(p => p.Id);
        builder.Property(p => p.Id).ValueGeneratedNever();

        builder.Property(p => p.Name).HasMaxLength(DetourLimits.DisplayNameMaxLength);
        builder.Property(p => p.Payload).HasColumnType(ColumnTypes.Jsonb);
        builder.Property(p => p.RadiusMeters);
        builder.Property(p => p.ClientPlaceId);
        builder.Property(p => p.CreatedAt);

        builder.HasOne<Group>()
            .WithMany()
            .HasForeignKey(p => p.GroupId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(p => p.OwnerId)
            .OnDelete(DeleteBehavior.NoAction);

        // Unique per (circle, owner, client id) — deliberately not per (circle, client id),
        // because the id is assigned on the owner's device and two members can pick the same
        // integer independently.
        builder.HasIndex(p => new { p.GroupId, p.OwnerId, p.ClientPlaceId }).IsUnique();

        builder.HasIndex(p => new { p.GroupId, p.CreatedAt });
    }
}

public class PlaceEventConfiguration : IEntityTypeConfiguration<PlaceEvent>
{
    public void Configure(EntityTypeBuilder<PlaceEvent> builder)
    {
        builder.ToTable("place_events");

        builder.HasKey(e => e.Id);
        builder.Property(e => e.Id).ValueGeneratedNever();

        builder.Property(e => e.Kind)
            .HasConversion<SmartEnumNameConverter<PlaceEventKind>>()
            .HasMaxLength(20);

        builder.Property(e => e.ClientPlaceId);
        builder.Property(e => e.TimestampMs);

        builder.HasOne<Group>()
            .WithMany()
            .HasForeignKey(e => e.GroupId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(e => e.UserId)
            .OnDelete(DeleteBehavior.NoAction);

        // The feed read (events since an instant) and the retention sweep both use this.
        builder.HasIndex(e => new { e.GroupId, e.TimestampMs });
    }
}
