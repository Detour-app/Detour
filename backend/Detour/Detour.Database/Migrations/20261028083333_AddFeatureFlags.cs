using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddFeatureFlags : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "feature_flags",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    key = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    enabled = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_feature_flags", x => x.id);
                    table.UniqueConstraint("ak_feature_flags_key", x => x.key);
                });

            migrationBuilder.CreateTable(
                name: "feature_flag_overrides",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    flag_key = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    enabled = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_feature_flag_overrides", x => x.id);
                    table.ForeignKey(
                        name: "fk_feature_flag_overrides_feature_flags_flag_key",
                        column: x => x.flag_key,
                        principalSchema: "detour",
                        principalTable: "feature_flags",
                        principalColumn: "key",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_feature_flag_overrides_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_feature_flag_overrides_flag_key_user_id",
                schema: "detour",
                table: "feature_flag_overrides",
                columns: new[] { "flag_key", "user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_feature_flag_overrides_user_id",
                schema: "detour",
                table: "feature_flag_overrides",
                column: "user_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "feature_flag_overrides",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "feature_flags",
                schema: "detour");
        }
    }
}
