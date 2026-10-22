using Detour.Domain;
using Detour.Domain.PlannedRides;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database.Converters;

namespace Detour.Database.EntityConfigurations;

public class PlannedRideConfiguration : IEntityTypeConfiguration<PlannedRide>
{
    public void Configure(EntityTypeBuilder<PlannedRide> builder)
    {
        builder.ToTable("planned_rides");

        builder.HasKey(r => r.Id);
        builder.Property(r => r.Id).ValueGeneratedNever();

        builder.Property(r => r.Title).HasMaxLength(DetourLimits.PlannedRideTitleMaxLength);
        builder.Property(r => r.StartsAt);
        builder.Property(r => r.MeetingLatitude);
        builder.Property(r => r.MeetingLongitude);
        builder.Property(r => r.MeetingLabel).HasMaxLength(DetourLimits.LabelMaxLength);
        builder.Property(r => r.ClientRouteId);
        builder.Property(r => r.Description).HasMaxLength(DetourLimits.PlannedRideDescriptionMaxLength);
        builder.Property(r => r.CreatedAt);
        builder.Property(r => r.RemindedForStartsAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(r => r.OwnerId)
            .OnDelete(DeleteBehavior.Cascade);

        // The aggregate owns its RSVPs: loading a ride without them would let a handler decide
        // who may see it from a partially-loaded object.
        builder.HasMany(r => r.Rsvps)
            .WithOne()
            .HasForeignKey(r => r.RideId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.Navigation(r => r.Rsvps)
            .UsePropertyAccessMode(PropertyAccessMode.Field)
            .HasField("_rsvps");

        builder.HasIndex(r => r.OwnerId);

        // The reminder sweep's range scan, once a minute.
        builder.HasIndex(r => r.StartsAt);
    }
}

public class PlannedRideRsvpConfiguration : IEntityTypeConfiguration<PlannedRideRsvp>
{
    public void Configure(EntityTypeBuilder<PlannedRideRsvp> builder)
    {
        builder.ToTable("planned_ride_rsvps");

        builder.HasKey(r => r.Id);
        builder.Property(r => r.Id).ValueGeneratedNever();

        builder.Property(r => r.Answer)
            .HasConversion<SmartEnumNameConverter<RsvpAnswer>>()
            .HasMaxLength(20);

        builder.Property(r => r.InvitedAt);
        builder.Property(r => r.AnsweredAt);

        // Deleting an invitee's account takes their RSVPs with it, the same way it takes their
        // group memberships.
        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(r => r.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(r => new { r.RideId, r.UserId }).IsUnique();

        // "Which rides am I invited to" — half of the list endpoint's query.
        builder.HasIndex(r => r.UserId);
    }
}
