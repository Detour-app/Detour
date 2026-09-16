using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddMunicipalityBoundaries : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "municipality_boundaries",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    source_id = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    rings_json = table.Column<string>(type: "jsonb", nullable: false),
                    bbox_min_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lat = table.Column<double>(type: "double precision", nullable: false),
                    bbox_min_lon = table.Column<double>(type: "double precision", nullable: false),
                    bbox_max_lon = table.Column<double>(type: "double precision", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_municipality_boundaries", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_municipality_boundaries_bbox_min_lat_bbox_max_lat_bbox_min_",
                schema: "detour",
                table: "municipality_boundaries",
                columns: new[] { "bbox_min_lat", "bbox_max_lat", "bbox_min_lon", "bbox_max_lon" });

            migrationBuilder.CreateIndex(
                name: "ix_municipality_boundaries_source_id",
                schema: "detour",
                table: "municipality_boundaries",
                column: "source_id",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "municipality_boundaries",
                schema: "detour");
        }
    }
}
