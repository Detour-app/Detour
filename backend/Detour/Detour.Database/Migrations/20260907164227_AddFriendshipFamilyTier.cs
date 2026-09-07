using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddFriendshipFamilyTier : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "family_requested_by_user_id",
                schema: "detour",
                table: "friendships",
                type: "uuid",
                nullable: true);

            // Existing friendships predate the family tier, so they backfill to None rather
            // than "" — the SmartEnum converter has no member for an empty string and would
            // throw reading such a row back.
            migrationBuilder.AddColumn<string>(
                name: "family_status",
                schema: "detour",
                table: "friendships",
                type: "character varying(20)",
                maxLength: 20,
                nullable: false,
                defaultValue: "None");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "family_requested_by_user_id",
                schema: "detour",
                table: "friendships");

            migrationBuilder.DropColumn(
                name: "family_status",
                schema: "detour",
                table: "friendships");
        }
    }
}
