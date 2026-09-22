using Detour.Domain.Roads;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class RoadWayConfiguration : IEntityTypeConfiguration<RoadWay>
{
    public void Configure(EntityTypeBuilder<RoadWay> builder)
    {
        builder.ToTable("roads");

        builder.HasKey(w => w.Id);

        builder.Property(w => w.SourceId).HasMaxLength(32).IsRequired();
        builder.Property(w => w.Highway).HasMaxLength(32).IsRequired();
        builder.Property(w => w.PolylineJson).HasColumnType("jsonb");

        // One row per OSM way: UpsertAsync looks this up on every import entry.
        builder.HasIndex(w => w.SourceId).IsUnique();

        // The one query this table exists to answer: does this way's bbox overlap the rider's
        // viewport? Same composite-btree call SpeedLimitWayConfiguration/CameraConfiguration make.
        builder.HasIndex(w => new { w.BboxMinLat, w.BboxMaxLat, w.BboxMinLon, w.BboxMaxLon });
    }
}
