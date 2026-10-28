using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddProblemReports : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "problem_reports",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    received_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    occurred_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    kind = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    platform = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    app_version = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                    os_version = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                    device_model = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                    note = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: true),
                    stack_trace = table.Column<string>(type: "text", nullable: true),
                    events = table.Column<string>(type: "jsonb", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: true),
                    filed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    filed_issue = table.Column<int>(type: "integer", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_problem_reports", x => x.id);
                    table.ForeignKey(
                        name: "fk_problem_reports_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_problem_reports_received_at",
                schema: "detour",
                table: "problem_reports",
                column: "received_at");

            migrationBuilder.CreateIndex(
                name: "ix_problem_reports_user_id",
                schema: "detour",
                table: "problem_reports",
                column: "user_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "problem_reports",
                schema: "detour");
        }
    }
}
