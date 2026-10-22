using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class CascadeAccountDeletion : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_circle_places_users_owner_id",
                schema: "detour",
                table: "circle_places");

            migrationBuilder.DropForeignKey(
                name: "fk_friendships_users_high_user_id",
                schema: "detour",
                table: "friendships");

            migrationBuilder.DropForeignKey(
                name: "fk_member_fixes_users_user_id",
                schema: "detour",
                table: "member_fixes");

            migrationBuilder.DropForeignKey(
                name: "fk_place_events_users_user_id",
                schema: "detour",
                table: "place_events");

            migrationBuilder.DropForeignKey(
                name: "fk_shared_routes_users_from_user_id",
                schema: "detour",
                table: "shared_routes");

            migrationBuilder.AddForeignKey(
                name: "fk_circle_places_users_owner_id",
                schema: "detour",
                table: "circle_places",
                column: "owner_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);

            migrationBuilder.AddForeignKey(
                name: "fk_friendships_users_high_user_id",
                schema: "detour",
                table: "friendships",
                column: "high_user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);

            migrationBuilder.AddForeignKey(
                name: "fk_member_fixes_users_user_id",
                schema: "detour",
                table: "member_fixes",
                column: "user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);

            migrationBuilder.AddForeignKey(
                name: "fk_place_events_users_user_id",
                schema: "detour",
                table: "place_events",
                column: "user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);

            migrationBuilder.AddForeignKey(
                name: "fk_shared_routes_users_from_user_id",
                schema: "detour",
                table: "shared_routes",
                column: "from_user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_circle_places_users_owner_id",
                schema: "detour",
                table: "circle_places");

            migrationBuilder.DropForeignKey(
                name: "fk_friendships_users_high_user_id",
                schema: "detour",
                table: "friendships");

            migrationBuilder.DropForeignKey(
                name: "fk_member_fixes_users_user_id",
                schema: "detour",
                table: "member_fixes");

            migrationBuilder.DropForeignKey(
                name: "fk_place_events_users_user_id",
                schema: "detour",
                table: "place_events");

            migrationBuilder.DropForeignKey(
                name: "fk_shared_routes_users_from_user_id",
                schema: "detour",
                table: "shared_routes");

            migrationBuilder.AddForeignKey(
                name: "fk_circle_places_users_owner_id",
                schema: "detour",
                table: "circle_places",
                column: "owner_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id");

            migrationBuilder.AddForeignKey(
                name: "fk_friendships_users_high_user_id",
                schema: "detour",
                table: "friendships",
                column: "high_user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id");

            migrationBuilder.AddForeignKey(
                name: "fk_member_fixes_users_user_id",
                schema: "detour",
                table: "member_fixes",
                column: "user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id");

            migrationBuilder.AddForeignKey(
                name: "fk_place_events_users_user_id",
                schema: "detour",
                table: "place_events",
                column: "user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id");

            migrationBuilder.AddForeignKey(
                name: "fk_shared_routes_users_from_user_id",
                schema: "detour",
                table: "shared_routes",
                column: "from_user_id",
                principalSchema: "detour",
                principalTable: "users",
                principalColumn: "id");
        }
    }
}
