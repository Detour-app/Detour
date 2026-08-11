using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Detour.Database.Migrations
{
    /// <inheritdoc />
    public partial class InitialSchema : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "detour");

            migrationBuilder.AlterDatabase()
                .Annotation("Npgsql:PostgresExtension:citext", ",,");

            migrationBuilder.CreateTable(
                name: "users",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    subject = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                    username = table.Column<string>(type: "citext", maxLength: 24, nullable: false),
                    email = table.Column<string>(type: "citext", maxLength: 254, nullable: true),
                    share_fog = table.Column<bool>(type: "boolean", nullable: false, defaultValue: false),
                    is_administrator = table.Column<bool>(type: "boolean", nullable: false, defaultValue: false),
                    stats_total_distance_meters = table.Column<double>(type: "double precision", nullable: false),
                    stats_top_speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    stats_longest_trip_meters = table.Column<double>(type: "double precision", nullable: false),
                    stats_max_lean_degrees = table.Column<double>(type: "double precision", nullable: true),
                    stats_municipalities_visited = table.Column<int>(type: "integer", nullable: false),
                    stats_best_coverage_percent = table.Column<double>(type: "double precision", nullable: false),
                    stats_trip_count = table.Column<int>(type: "integer", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_users", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "api_keys",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    key_hash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    label = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_used_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_api_keys", x => x.id);
                    table.ForeignKey(
                        name: "fk_api_keys_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "badge_awards",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    badge_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    earned_at_ms = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_badge_awards", x => x.id);
                    table.ForeignKey(
                        name: "fk_badge_awards_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "friendships",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    low_user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    high_user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    status = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    requested_by_user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    accepted_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_friendships", x => x.id);
                    table.ForeignKey(
                        name: "fk_friendships_users_high_user_id",
                        column: x => x.high_user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id");
                    table.ForeignKey(
                        name: "fk_friendships_users_low_user_id",
                        column: x => x.low_user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "groups",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    name = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    owner_id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_groups", x => x.id);
                    table.ForeignKey(
                        name: "fk_groups_users_owner_id",
                        column: x => x.owner_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "saved_places",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    client_place_id = table.Column<long>(type: "bigint", nullable: false),
                    payload = table.Column<string>(type: "jsonb", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_saved_places", x => x.id);
                    table.ForeignKey(
                        name: "fk_saved_places_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "shared_routes",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    from_user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    to_user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    client_route_id = table.Column<long>(type: "bigint", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    payload = table.Column<string>(type: "jsonb", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_shared_routes", x => x.id);
                    table.ForeignKey(
                        name: "fk_shared_routes_users_from_user_id",
                        column: x => x.from_user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id");
                    table.ForeignKey(
                        name: "fk_shared_routes_users_to_user_id",
                        column: x => x.to_user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "traces",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    line_hash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    line = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_traces", x => x.id);
                    table.ForeignKey(
                        name: "fk_traces_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "track_points",
                schema: "detour",
                columns: table => new
                {
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    timestamp_ms = table.Column<long>(type: "bigint", nullable: false),
                    latitude = table.Column<double>(type: "double precision", nullable: false),
                    longitude = table.Column<double>(type: "double precision", nullable: false),
                    speed_kmh = table.Column<double>(type: "double precision", nullable: true),
                    lean_degrees = table.Column<double>(type: "double precision", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_track_points", x => new { x.user_id, x.timestamp_ms });
                    table.ForeignKey(
                        name: "fk_track_points_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "trips",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    start_time_ms = table.Column<long>(type: "bigint", nullable: false),
                    end_time_ms = table.Column<long>(type: "bigint", nullable: true),
                    distance_meters = table.Column<double>(type: "double precision", nullable: false),
                    top_speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    max_g_force = table.Column<double>(type: "double precision", nullable: true),
                    mode = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: true),
                    payload = table.Column<string>(type: "jsonb", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_trips", x => x.id);
                    table.ForeignKey(
                        name: "fk_trips_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "circle_places",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    group_id = table.Column<Guid>(type: "uuid", nullable: false),
                    owner_id = table.Column<Guid>(type: "uuid", nullable: false),
                    client_place_id = table.Column<long>(type: "bigint", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    radius_meters = table.Column<double>(type: "double precision", nullable: false),
                    payload = table.Column<string>(type: "jsonb", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_circle_places", x => x.id);
                    table.ForeignKey(
                        name: "fk_circle_places_groups_group_id",
                        column: x => x.group_id,
                        principalSchema: "detour",
                        principalTable: "groups",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_circle_places_users_owner_id",
                        column: x => x.owner_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id");
                });

            migrationBuilder.CreateTable(
                name: "group_members",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    group_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    status = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    joined_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    is_sharing = table.Column<bool>(type: "boolean", nullable: false, defaultValue: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_group_members", x => x.id);
                    table.ForeignKey(
                        name: "fk_group_members_groups_group_id",
                        column: x => x.group_id,
                        principalSchema: "detour",
                        principalTable: "groups",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_group_members_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "member_fixes",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    group_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    latitude = table.Column<double>(type: "double precision", nullable: false),
                    longitude = table.Column<double>(type: "double precision", nullable: false),
                    accuracy_meters = table.Column<double>(type: "double precision", nullable: true),
                    timestamp_ms = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_member_fixes", x => x.id);
                    table.ForeignKey(
                        name: "fk_member_fixes_groups_group_id",
                        column: x => x.group_id,
                        principalSchema: "detour",
                        principalTable: "groups",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_member_fixes_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id");
                });

            migrationBuilder.CreateTable(
                name: "place_events",
                schema: "detour",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    group_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    client_place_id = table.Column<long>(type: "bigint", nullable: false),
                    kind = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    timestamp_ms = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_place_events", x => x.id);
                    table.ForeignKey(
                        name: "fk_place_events_groups_group_id",
                        column: x => x.group_id,
                        principalSchema: "detour",
                        principalTable: "groups",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_place_events_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "detour",
                        principalTable: "users",
                        principalColumn: "id");
                });

            migrationBuilder.CreateIndex(
                name: "ix_api_keys_key_hash",
                schema: "detour",
                table: "api_keys",
                column: "key_hash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_api_keys_user_id",
                schema: "detour",
                table: "api_keys",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_badge_awards_user_id_badge_id",
                schema: "detour",
                table: "badge_awards",
                columns: new[] { "user_id", "badge_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_circle_places_group_id_created_at",
                schema: "detour",
                table: "circle_places",
                columns: new[] { "group_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_circle_places_group_id_owner_id_client_place_id",
                schema: "detour",
                table: "circle_places",
                columns: new[] { "group_id", "owner_id", "client_place_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_circle_places_owner_id",
                schema: "detour",
                table: "circle_places",
                column: "owner_id");

            migrationBuilder.CreateIndex(
                name: "ix_friendships_high_user_id",
                schema: "detour",
                table: "friendships",
                column: "high_user_id");

            migrationBuilder.CreateIndex(
                name: "ix_friendships_low_user_id_high_user_id",
                schema: "detour",
                table: "friendships",
                columns: new[] { "low_user_id", "high_user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_group_members_group_id_user_id",
                schema: "detour",
                table: "group_members",
                columns: new[] { "group_id", "user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_group_members_user_id",
                schema: "detour",
                table: "group_members",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_groups_kind_owner_id",
                schema: "detour",
                table: "groups",
                columns: new[] { "kind", "owner_id" });

            migrationBuilder.CreateIndex(
                name: "ix_groups_owner_id",
                schema: "detour",
                table: "groups",
                column: "owner_id");

            migrationBuilder.CreateIndex(
                name: "ix_member_fixes_group_id_user_id",
                schema: "detour",
                table: "member_fixes",
                columns: new[] { "group_id", "user_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_member_fixes_user_id",
                schema: "detour",
                table: "member_fixes",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_place_events_group_id_timestamp_ms",
                schema: "detour",
                table: "place_events",
                columns: new[] { "group_id", "timestamp_ms" });

            migrationBuilder.CreateIndex(
                name: "ix_place_events_user_id",
                schema: "detour",
                table: "place_events",
                column: "user_id");

            migrationBuilder.CreateIndex(
                name: "ix_saved_places_user_id_client_place_id",
                schema: "detour",
                table: "saved_places",
                columns: new[] { "user_id", "client_place_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_shared_routes_from_user_id",
                schema: "detour",
                table: "shared_routes",
                column: "from_user_id");

            migrationBuilder.CreateIndex(
                name: "ix_shared_routes_to_user_id_created_at",
                schema: "detour",
                table: "shared_routes",
                columns: new[] { "to_user_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_shared_routes_to_user_id_from_user_id_client_route_id",
                schema: "detour",
                table: "shared_routes",
                columns: new[] { "to_user_id", "from_user_id", "client_route_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_traces_user_id_line_hash",
                schema: "detour",
                table: "traces",
                columns: new[] { "user_id", "line_hash" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_trips_user_id_start_time_ms",
                schema: "detour",
                table: "trips",
                columns: new[] { "user_id", "start_time_ms" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_users_email",
                schema: "detour",
                table: "users",
                column: "email");

            migrationBuilder.CreateIndex(
                name: "ix_users_subject",
                schema: "detour",
                table: "users",
                column: "subject",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_users_username",
                schema: "detour",
                table: "users",
                column: "username",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "api_keys",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "badge_awards",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "circle_places",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "friendships",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "group_members",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "member_fixes",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "place_events",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "saved_places",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "shared_routes",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "traces",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "track_points",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "trips",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "groups",
                schema: "detour");

            migrationBuilder.DropTable(
                name: "users",
                schema: "detour");
        }
    }
}
