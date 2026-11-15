using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class RestrictGroupOwnerDelete : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_groups_users_owner_id",
                schema: "detour",
                table: "groups");

            migrationBuilder.AddForeignKey(
                name: "fk_groups_users_owner_id",
                schema: "detour",
                table: "groups",
                column: "owner_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_groups_users_owner_id",
                schema: "detour",
                table: "groups");

            migrationBuilder.AddForeignKey(
                name: "fk_groups_users_owner_id",
                schema: "detour",
                table: "groups",
                column: "owner_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);
        }
    }
}
