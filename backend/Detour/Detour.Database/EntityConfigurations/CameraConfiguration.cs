using Detour.Domain.Cameras;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class CameraConfiguration : IEntityTypeConfiguration<Camera>
{
    public void Configure(EntityTypeBuilder<Camera> builder)
    {
        builder.ToTable("cameras");

        builder.HasKey(c => c.Id);
        builder.Property(c => c.Id).ValueGeneratedNever();

        builder.Property(c => c.Kind).HasConversion<string>().HasMaxLength(32);
        builder.Property(c => c.Status).HasConversion<string>().HasMaxLength(16);
        builder.Property(c => c.RoadRef).HasMaxLength(64);
        builder.Property(c => c.PolylineJson).HasColumnType("jsonb");
        builder.Property(c => c.SourcesJson).HasColumnName("sources_json").HasColumnType("jsonb");

        // Sources is a lazily-parsed view over SourcesJson (see Camera.cs), not an independent
        // property — without this, EF's convention discovery still tries to map CameraSource as
        // its own entity type and fails for lack of a key.
        builder.Ignore(c => c.Sources);

        // The one query this table exists to answer: does this camera's bbox overlap the
        // rider's viewport? A composite btree, not PostGIS/GiST — see the plan's Global
        // Constraints for why that's the right call at this row count.
        builder.HasIndex(c => new { c.BboxMinLat, c.BboxMaxLat, c.BboxMinLon, c.BboxMaxLon });
        builder.HasIndex(c => c.Status);
    }
}
