using Detour.Domain.ServerSecrets;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class ServerSecretConfiguration : IEntityTypeConfiguration<ServerSecret>
{
    public void Configure(EntityTypeBuilder<ServerSecret> builder)
    {
        builder.ToTable("server_secrets");

        builder.HasKey(s => s.Name);
        builder.Property(s => s.Name).HasMaxLength(64);
        builder.Property(s => s.Value);
    }
}
