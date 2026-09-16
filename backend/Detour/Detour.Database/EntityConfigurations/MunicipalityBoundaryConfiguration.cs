using Detour.Domain.Boundaries;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Detour.Database.EntityConfigurations;

public class MunicipalityBoundaryConfiguration : IEntityTypeConfiguration<MunicipalityBoundary>
{
    public void Configure(EntityTypeBuilder<MunicipalityBoundary> builder)
    {
        builder.ToTable("municipality_boundaries");

        builder.HasKey(b => b.Id);

        builder.Property(b => b.SourceId).HasMaxLength(32).IsRequired();
        builder.Property(b => b.Name).HasMaxLength(200).IsRequired();
        builder.Property(b => b.RingsJson).HasColumnType("jsonb");

        // Computed from SourceId, not a column.
        builder.Ignore(b => b.OsmId);

        // One row per OSM relation: UpsertAsync looks this up on every import entry.
        builder.HasIndex(b => b.SourceId).IsUnique();

        // The one query this table exists to answer: does this row's bbox contain the rider's
        // point? Same composite-btree pre-filter CameraConfiguration/SpeedLimitWayConfiguration
        // make, for the same reason.
        builder.HasIndex(b => new { b.BboxMinLat, b.BboxMaxLat, b.BboxMinLon, b.BboxMaxLon });
    }
}
