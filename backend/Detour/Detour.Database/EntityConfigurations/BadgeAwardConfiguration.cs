using Detour.Domain;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class BadgeAwardConfiguration : IEntityTypeConfiguration<BadgeAward>
{
    public void Configure(EntityTypeBuilder<BadgeAward> builder)
    {
        builder.ToTable("badge_awards");

        builder.HasKey(b => b.Id);
        builder.Property(b => b.Id).ValueGeneratedNever();

        builder.Property(b => b.BadgeId).HasMaxLength(DetourLimits.BadgeIdMaxLength);
        builder.Property(b => b.EarnedAtMs);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(b => b.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // One award per badge per rider — this is what makes "keep the earliest instant seen" a
        // database invariant rather than something the merge code has to remember.
        builder.HasIndex(b => new { b.UserId, b.BadgeId }).IsUnique();
    }
}
