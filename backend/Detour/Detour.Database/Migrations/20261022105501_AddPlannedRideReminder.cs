using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddPlannedRideReminder : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "reminded_for_starts_at",
                schema: "detour",
                table: "planned_rides",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "ix_planned_rides_starts_at",
                schema: "detour",
                table: "planned_rides",
                column: "starts_at");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_planned_rides_starts_at",
                schema: "detour",
                table: "planned_rides");

            migrationBuilder.DropColumn(
                name: "reminded_for_starts_at",
                schema: "detour",
                table: "planned_rides");
        }
    }
}
