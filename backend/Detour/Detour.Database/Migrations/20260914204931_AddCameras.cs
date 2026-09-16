using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddCameras : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "cameras",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    lat = table.Column<double>(type: "double precision", nullable: true),
                    lon = table.Column<double>(type: "double precision", nullable: true),
                    polyline_json = table.Column<string>(type: "jsonb", nullable: true),
                    bbox_min_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_min_lon = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lon = table.Column<double>(type: "double precision", nullable: false),
                    max_speed_kmh = table.Column<int>(type: "integer", nullable: true),
                    road_ref = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: true),
                    sources_json = table.Column<string>(type: "jsonb", nullable: false),
                    status = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    first_seen = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_cameras", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_cameras_bbox_min_lat_bbox_max_lat_bbox_min_lon_bbox_max_lon",
                schema: "detour",
                table: "cameras",
                columns: new[] { "bbox_min_lat", "bbox_max_lat", "bbox_min_lon", "bbox_max_lon" });

            migrationBuilder.CreateIndex(
                name: "ix_cameras_status",
                schema: "detour",
                table: "cameras",
                column: "status");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "cameras",
                schema: "detour");
        }
    }
}
