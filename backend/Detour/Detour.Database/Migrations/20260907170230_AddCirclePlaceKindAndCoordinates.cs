using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddCirclePlaceKindAndCoordinates : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Existing shared places predate the kind field and are not homes, so they backfill
            // to NONE — never "HOME", so the withholding rule leaves them exactly as they were.
            migrationBuilder.AddColumn<string>(
                name: "kind",
                schema: "detour",
                table: "circle_places",
                type: "character varying(20)",
                maxLength: 20,
                nullable: false,
                defaultValue: "NONE");

            migrationBuilder.AddColumn<double>(
                name: "lat",
                schema: "detour",
                table: "circle_places",
                type: "double precision",
                nullable: true);

            migrationBuilder.AddColumn<double>(
                name: "lon",
                schema: "detour",
                table: "circle_places",
                type: "double precision",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "kind",
                schema: "detour",
                table: "circle_places");

            migrationBuilder.DropColumn(
                name: "lat",
                schema: "detour",
                table: "circle_places");

            migrationBuilder.DropColumn(
                name: "lon",
                schema: "detour",
                table: "circle_places");
        }
    }
}
