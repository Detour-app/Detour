using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddSavedRouteStampAndTombstones : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<long>(
                name: "edited_at_ms",
                schema: "detour",
                table: "saved_routes",
                type: "bigint",
                nullable: false,
                defaultValue: 0L);

            migrationBuilder.CreateTable(
                name: "saved_route_tombstones",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    client_route_id = table.Column<long>(type: "bigint", nullable: false),
                    deleted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_saved_route_tombstones", x => x.id);
                    table.ForeignKey(
                        name: "fk_saved_route_tombstones_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_saved_route_tombstones_user_id_client_route_id",
                schema: "detour",
                table: "saved_route_tombstones",
                columns: new[] { "user_id", "client_route_id" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "saved_route_tombstones",
                schema: "detour");

            migrationBuilder.DropColumn(
                name: "edited_at_ms",
                schema: "detour",
                table: "saved_routes");
        }
    }
}
