using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddPois : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "pois",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    source_id = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    kind = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    name = table.Column<string>(type: "character varying(256)", maxLength: 256, nullable: false),
                    lat = table.Column<double>(type: "double precision", nullable: false),
                    lon = table.Column<double>(type: "double precision", nullable: false),
                    bbox_min_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_min_lon = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lon = table.Column<double>(type: "double precision", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_pois", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_pois_bbox_min_lat_bbox_max_lat_bbox_min_lon_bbox_max_lon",
                schema: "detour",
                table: "pois",
                columns: new[] { "bbox_min_lat", "bbox_max_lat", "bbox_min_lon", "bbox_max_lon" });

            migrationBuilder.CreateIndex(
                name: "ix_pois_source_id",
                schema: "detour",
                table: "pois",
                column: "source_id",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "pois",
                schema: "detour");
        }
    }
}
