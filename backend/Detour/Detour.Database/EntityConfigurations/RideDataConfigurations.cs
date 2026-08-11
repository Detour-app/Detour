using Detour.Domain.Places;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database;

namespace Detour.Database.EntityConfigurations;

public class TripConfiguration : IEntityTypeConfiguration<Trip>
{
    public void Configure(EntityTypeBuilder<Trip> builder)
    {
        builder.ToTable("trips");

        builder.HasKey(t => t.Id);
        builder.Property(t => t.Id).ValueGeneratedNever();

        // jsonb rather than text: the payload stays opaque to this backend, but storing it as a
        // document means a future read path can index into it without a migration that rewrites
        // every row.
        builder.Property(t => t.Payload).HasColumnType(ColumnTypes.Jsonb);

        builder.Property(t => t.Mode).HasMaxLength(32);
        builder.Property(t => t.StartTimeMs);
        builder.Property(t => t.EndTimeMs);
        builder.Property(t => t.UpdatedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(t => t.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // The natural key. A re-upload of an edited trip must replace the stored copy, and this
        // is what the upsert matches on.
        builder.HasIndex(t => new { t.UserId, t.StartTimeMs }).IsUnique();
    }
}

public class TraceConfiguration : IEntityTypeConfiguration<Trace>
{
    public void Configure(EntityTypeBuilder<Trace> builder)
    {
        builder.ToTable("traces");

        builder.HasKey(t => t.Id);
        builder.Property(t => t.Id).ValueGeneratedNever();

        // Hex SHA-256.
        builder.Property(t => t.LineHash).HasMaxLength(64);
        builder.Property(t => t.Line);
        builder.Property(t => t.CreatedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(t => t.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // Every sync re-sends the whole history, so this index is the hot path: it is what makes
        // "have I already got this line" one lookup instead of a content comparison.
        builder.HasIndex(t => new { t.UserId, t.LineHash }).IsUnique();
    }
}

public class TrackPointConfiguration : IEntityTypeConfiguration<TrackPoint>
{
    public void Configure(EntityTypeBuilder<TrackPoint> builder)
    {
        builder.ToTable("track_points");

        // Composite natural key, no surrogate: this is the one table that grows per recorded
        // sample rather than per user action. It is also the index every dashboard read uses,
        // because a point belongs to whichever ride's time window contains it.
        builder.HasKey(p => new { p.UserId, p.TimestampMs });

        builder.Property(p => p.Latitude);
        builder.Property(p => p.Longitude);
        builder.Property(p => p.SpeedKmh);
        builder.Property(p => p.LeanDegrees);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(p => p.UserId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}

public class SavedPlaceConfiguration : IEntityTypeConfiguration<SavedPlace>
{
    public void Configure(EntityTypeBuilder<SavedPlace> builder)
    {
        builder.ToTable("saved_places");

        builder.HasKey(p => p.Id);
        builder.Property(p => p.Id).ValueGeneratedNever();

        builder.Property(p => p.Payload).HasColumnType(ColumnTypes.Jsonb);
        builder.Property(p => p.ClientPlaceId);
        builder.Property(p => p.UpdatedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(p => p.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(p => new { p.UserId, p.ClientPlaceId }).IsUnique();
    }
}
