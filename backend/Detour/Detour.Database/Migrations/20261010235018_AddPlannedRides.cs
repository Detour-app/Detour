using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddPlannedRides : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "planned_rides",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    owner_id = table.Column<Guid>(type: "uuid", nullable: false),
                    title = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: false),
                    starts_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    meeting_latitude = table.Column<double>(type: "double precision", nullable: false),
                    meeting_longitude = table.Column<double>(type: "double precision", nullable: false),
                    meeting_label = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: true),
                    client_route_id = table.Column<long>(type: "bigint", nullable: true),
                    description = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_planned_rides", x => x.id);
                    table.ForeignKey(
                        name: "fk_planned_rides_users_owner_id",
                        column: x => x.owner_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "planned_ride_rsvps",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    ride_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    answer = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    invited_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    answered_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_planned_ride_rsvps", x => x.id);
                    table.ForeignKey(
                        name: "fk_planned_ride_rsvps_planned_rides_ride_id",
                        column: x => x.ride_id,
                        principalSchema: "detour",
                        principalTable: "planned_rides",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_planned_ride_rsvps_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_planned_ride_rsvps_ride_id_user_id",
                schema: "detour",
                table: "planned_ride_rsvps",
                columns: new[] { "ride_id", "user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_planned_ride_rsvps_user_id",
                schema: "detour",
                table: "planned_ride_rsvps",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_planned_rides_owner_id",
                schema: "detour",
                table: "planned_rides",
                column: "owner_id");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "planned_ride_rsvps",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "planned_rides",
                schema: "detour");
        }
    }
}
