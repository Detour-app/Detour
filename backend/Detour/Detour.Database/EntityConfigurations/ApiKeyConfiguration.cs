using Detour.Domain;
using Detour.Domain.ApiKeys;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class ApiKeyConfiguration : IEntityTypeConfiguration<ApiKey>
{
    public void Configure(EntityTypeBuilder<ApiKey> builder)
    {
        builder.ToTable("api_keys");

        builder.HasKey(k => k.Id);
        builder.Property(k => k.Id).ValueGeneratedNever();

        // Hex SHA-256. The plaintext is never stored, so this column is the whole credential
        // record — a database leak hands over nothing that can be replayed.
        builder.Property(k => k.KeyHash).HasMaxLength(64);
        builder.Property(k => k.Label).HasMaxLength(DetourLimits.LabelMaxLength);
        builder.Property(k => k.CreatedAt);
        builder.Property(k => k.LastUsedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(k => k.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // The authentication lookup, on every dashboard request.
        builder.HasIndex(k => k.KeyHash).IsUnique();
        builder.HasIndex(k => k.UserId);
    }
}
