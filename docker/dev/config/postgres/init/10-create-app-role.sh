#!/bin/sh
# The backend connects as a least-privileged role, not as the superuser.
#
# It owns the detour schema (so migrations can create and alter within it) and
# nothing else — it cannot create databases, cannot read other databases, and
# cannot install extensions outside what it already has. That is the concrete
# improvement over the SQLite file this replaces, where "the process can write
# the file" was the whole permission model.
#
# Runs once, on an empty data directory. Changing it later means wiping the
# postgres-data volume or applying the change by hand.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE "${DETOUR_DB_USER}" WITH LOGIN PASSWORD '${DETOUR_DB_PASSWORD}';

    -- citext is created by the first migration, which needs to be superuser-
    -- adjacent to do it. Installing it here instead keeps the application role
    -- from ever needing CREATE EXTENSION.
    CREATE EXTENSION IF NOT EXISTS citext;

    CREATE SCHEMA IF NOT EXISTS detour AUTHORIZATION "${DETOUR_DB_USER}";

    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${DETOUR_DB_USER}";
    GRANT USAGE ON SCHEMA public TO "${DETOUR_DB_USER}";
EOSQL
