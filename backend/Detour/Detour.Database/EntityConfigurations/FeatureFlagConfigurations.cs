using Detour.Domain;
using Detour.Domain.FeatureFlags;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class FeatureFlagConfiguration : IEntityTypeConfiguration<FeatureFlag>
{
    public void Configure(EntityTypeBuilder<FeatureFlag> builder)
    {
        builder.ToTable("feature_flags");

        builder.HasKey(f => f.Id);
        builder.Property(f => f.Id).ValueGeneratedNever();

        // An alternate key rather than a unique index: overrides reference the flag by its key, so
        // a row in feature_flag_overrides reads on its own and the admin endpoints (#813) address
        // both by the same string.
        builder.Property(f => f.Key).HasMaxLength(DetourLimits.FeatureFlagKeyMaxLength);
        builder.HasAlternateKey(f => f.Key);

        builder.Property(f => f.Enabled);
        builder.Property(f => f.UpdatedAt);
    }
}

public class FeatureFlagOverrideConfiguration : IEntityTypeConfiguration<FeatureFlagOverride>
{
    public void Configure(EntityTypeBuilder<FeatureFlagOverride> builder)
    {
        builder.ToTable("feature_flag_overrides");

        builder.HasKey(o => o.Id);
        builder.Property(o => o.Id).ValueGeneratedNever();

        builder.Property(o => o.FlagKey).HasMaxLength(DetourLimits.FeatureFlagKeyMaxLength);
        builder.Property(o => o.Enabled);

        builder.HasOne<FeatureFlag>()
            .WithMany()
            .HasForeignKey(o => o.FlagKey)
            .HasPrincipalKey(f => f.Key)
            .OnDelete(DeleteBehavior.Cascade);

        // Deleting an account takes its overrides with it (BACKEND_SPEC §13).
        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(o => o.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        builder.HasIndex(o => new { o.FlagKey, o.UserId }).IsUnique();

        // The read endpoint's lookup: one account's overrides.
        builder.HasIndex(o => o.UserId);
    }
}
