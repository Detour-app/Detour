using Detour.Domain.Notifications;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database.Converters;

namespace Detour.Database.EntityConfigurations;

public class DeviceTokenConfiguration : IEntityTypeConfiguration<DeviceToken>
{
    public void Configure(EntityTypeBuilder<DeviceToken> builder)
    {
        builder.ToTable("device_tokens");

        builder.HasKey(t => t.Id);
        builder.Property(t => t.Id).ValueGeneratedNever();

        builder.Property(t => t.Token).HasMaxLength(4096);
        builder.Property(t => t.Platform)
            .HasConversion<SmartEnumNameConverter<DevicePlatform>>()
            .HasMaxLength(20);
        builder.Property(t => t.CreatedAt);
        builder.Property(t => t.LastRefreshedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(t => t.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // One row per install. Reassigned, never duplicated, when the install
        // switches accounts.
        builder.HasIndex(t => t.Token).IsUnique();
        // The fan-out reads by user.
        builder.HasIndex(t => t.UserId);
    }
}
