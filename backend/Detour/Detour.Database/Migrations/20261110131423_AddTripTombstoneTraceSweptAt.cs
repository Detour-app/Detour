using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class AddTripTombstoneTraceSweptAt : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "trace_swept_at",
                schema: "detour",
                table: "trip_tombstones",
                type: "timestamp with time zone",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "trace_swept_at",
                schema: "detour",
                table: "trip_tombstones");
        }
    }
}
