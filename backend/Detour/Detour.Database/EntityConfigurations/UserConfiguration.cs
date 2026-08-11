using Detour.Domain;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class UserConfiguration : IEntityTypeConfiguration<User>
{
    public void Configure(EntityTypeBuilder<User> builder)
    {
        builder.ToTable("users");

        builder.HasKey(u => u.Id);
        builder.Property(u => u.Id).ValueGeneratedNever();

        builder.Property(u => u.Subject).HasMaxLength(DetourLimits.SubjectMaxLength);

        // citext, not text: handles and addresses are compared case-insensitively everywhere a
        // rider looks one up, so uniqueness has to be enforced the same way. A plain unique
        // index would happily admit both "Rider" and "rider" and then hand a friend request to
        // whichever one the query happened to match.
        builder.Property(u => u.Username)
            .HasColumnType("citext")
            .HasMaxLength(DetourLimits.UsernameMaxLength);

        builder.Property(u => u.Email)
            .HasColumnType("citext")
            .HasMaxLength(DetourLimits.EmailMaxLength);

        builder.Property(u => u.ShareFog).HasDefaultValue(false);
        builder.Property(u => u.IsAdministrator).HasDefaultValue(false);

        builder.Property(u => u.CreatedAt);
        builder.Property(u => u.LastSeenAt);

        // Flattened into the users table with a stats_ prefix: the friend leaderboard sorts on
        // total distance, and sorting inside a JSON document is not worth the trade.
        builder.OwnsOne(u => u.Stats, stats =>
        {
            stats.Property(s => s.TotalDistanceMeters).HasColumnName("stats_total_distance_meters");
            stats.Property(s => s.TopSpeedKmh).HasColumnName("stats_top_speed_kmh");
            stats.Property(s => s.LongestTripMeters).HasColumnName("stats_longest_trip_meters");
            stats.Property(s => s.MaxLeanDegrees).HasColumnName("stats_max_lean_degrees");
            stats.Property(s => s.MunicipalitiesVisited).HasColumnName("stats_municipalities_visited");
            stats.Property(s => s.BestCoveragePercent).HasColumnName("stats_best_coverage_percent");
            stats.Property(s => s.TripCount).HasColumnName("stats_trip_count");
        });
        builder.Navigation(u => u.Stats).IsRequired();

        // The subject is the only link to the identity provider, so it has to be unique and it
        // is the lookup every authenticated request performs.
        builder.HasIndex(u => u.Subject).IsUnique();

        builder.HasIndex(u => u.Username).IsUnique();
        builder.HasIndex(u => u.Email);
    }
}
