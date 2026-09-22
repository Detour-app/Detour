using Detour.Domain.Pois;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class PoiConfiguration : IEntityTypeConfiguration<Poi>
{
    public void Configure(EntityTypeBuilder<Poi> builder)
    {
        builder.ToTable("pois");

        builder.HasKey(p => p.Id);

        builder.Property(p => p.SourceId).HasMaxLength(32).IsRequired();
        builder.Property(p => p.Kind).HasMaxLength(16).IsRequired();
        builder.Property(p => p.Name).HasMaxLength(256).IsRequired();

        // One row per OSM element: UpsertAsync looks this up on every import entry.
        builder.HasIndex(p => p.SourceId).IsUnique();

        // The one query this table exists to answer: does this POI's point overlap the rider's
        // sampled sub-area? Same composite-btree call RoadWayConfiguration/CameraConfiguration make.
        builder.HasIndex(p => new { p.BboxMinLat, p.BboxMaxLat, p.BboxMinLon, p.BboxMaxLon });
    }
}
