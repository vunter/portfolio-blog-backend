#!/bin/bash
set -euo pipefail

# ============================================
# PostgreSQL init script for cloud deployment.
# Creates the 'blogadmin' app user with only the privileges the application
# actually needs (CRUD on existing and future tables, plus USAGE/SELECT on
# sequences). Schema-altering rights stay with $POSTGRES_USER so an SQL
# injection against the app user cannot evolve the schema or truncate
# unrelated tables.
# Runs automatically on first container start.
# ============================================

: "${POSTGRES_USER:?POSTGRES_USER must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"
: "${DB_PASSWORD:?DB_PASSWORD must be set}"

# Pass the password as a psql variable so we don't interpolate it directly
# into the SQL heredoc (avoids quoting issues / partial leaks via -x).
PGPASSWORD="$DB_PASSWORD" \
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v "app_pwd=$DB_PASSWORD" <<-'EOSQL'
    -- Create app user with the password supplied via the :app_pwd variable
    -- (psql double-quotes it; we never embed it in a literal SQL string).
    DO $$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'blogadmin') THEN
            EXECUTE format('CREATE USER blogadmin WITH PASSWORD %L', :'app_pwd');
        ELSE
            EXECUTE format('ALTER USER blogadmin WITH PASSWORD %L', :'app_pwd');
        END IF;
    END
    $$;

    -- Connection + schema usage
    GRANT CONNECT ON DATABASE blog TO blogadmin;
    GRANT USAGE ON SCHEMA public TO blogadmin;

    -- CRUD on existing tables and sequences (no TRUNCATE, no REFERENCES, no
    -- TRIGGER; if those become necessary, grant them per-table explicitly).
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO blogadmin;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO blogadmin;

    -- And on tables/sequences created after this script runs by the
    -- $POSTGRES_USER role.
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO blogadmin;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO blogadmin;
EOSQL

echo "User 'blogadmin' created with CRUD + sequence USAGE/SELECT on 'blog'"

# Run the application schema (tables, indexes, etc.)
if [ -f /docker-entrypoint-initdb.d/schema.sql ]; then
    echo "Applying database schema..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        -f /docker-entrypoint-initdb.d/schema.sql
    echo "Database schema applied successfully"
else
    echo "schema.sql not found - skipping schema initialization"
fi
