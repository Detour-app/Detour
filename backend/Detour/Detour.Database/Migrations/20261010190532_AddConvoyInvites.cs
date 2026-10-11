using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddConvoyInvites : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "convoy_invites",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    group_id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_by = table.Column<Guid>(type: "uuid", nullable: false),
                    token_hash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    revoked_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_convoy_invites", x => x.id);
                    table.ForeignKey(
                        name: "fk_convoy_invites_groups_group_id",
                        column: x => x.group_id,
                        principalSchema: "detour",
                        principalTable: "groups",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_convoy_invites_users_created_by",
                        column: x => x.created_by,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "convoy_guests",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    invite_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    joined_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    removed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_convoy_guests", x => x.id);
                    table.ForeignKey(
                        name: "fk_convoy_guests_convoy_invites_invite_id",
                        column: x => x.invite_id,
                        principalSchema: "detour",
                        principalTable: "convoy_invites",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_convoy_guests_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_convoy_guests_invite_id_user_id",
                schema: "detour",
                table: "convoy_guests",
                columns: new[] { "invite_id", "user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_convoy_guests_user_id",
                schema: "detour",
                table: "convoy_guests",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_convoy_invites_created_by",
                schema: "detour",
                table: "convoy_invites",
                column: "created_by");

            migrationBuilder.CreateIndex(
                name: "ix_convoy_invites_group_id",
                schema: "detour",
                table: "convoy_invites",
                column: "group_id");

            migrationBuilder.CreateIndex(
                name: "ix_convoy_invites_token_hash",
                schema: "detour",
                table: "convoy_invites",
                column: "token_hash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "convoy_guests",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "convoy_invites",
                schema: "detour");
        }
    }
}
