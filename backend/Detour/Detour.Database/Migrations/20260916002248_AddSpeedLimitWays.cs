using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddSpeedLimitWays : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "speed_limit_ways",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    source_id = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    max_speed_kmh = table.Column<int>(type: "integer", nullable: false),
                    polyline_json = table.Column<string>(type: "jsonb", nullable: false),
                    bbox_min_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_min_lon = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lon = table.Column<double>(type: "double precision", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_speed_limit_ways", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_speed_limit_ways_bbox_min_lat_bbox_max_lat_bbox_min_lon_bbo",
                schema: "detour",
                table: "speed_limit_ways",
                columns: new[] { "bbox_min_lat", "bbox_max_lat", "bbox_min_lon", "bbox_max_lon" });

            migrationBuilder.CreateIndex(
                name: "ix_speed_limit_ways_source_id",
                schema: "detour",
                table: "speed_limit_ways",
                column: "source_id",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "speed_limit_ways",
                schema: "detour");
        }
    }
}
