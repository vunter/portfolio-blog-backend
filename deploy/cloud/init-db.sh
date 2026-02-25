#!/bin/bash
set -e

# ============================================
# PostgreSQL init script for cloud deployment
# Creates limited 'blogadmin' app user and
# initializes the database schema
# Runs automatically on first container start
# ============================================

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create app user with limited privileges (idempotent)
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'blogadmin') THEN
            CREATE USER blogadmin WITH PASSWORD '$DB_PASSWORD';
        ELSE
            ALTER USER blogadmin WITH PASSWORD '$DB_PASSWORD';
        END IF;
    END
    \$\$;

    -- Grant connection and schema usage
    GRANT CONNECT ON DATABASE blog TO blogadmin;
    GRANT USAGE ON SCHEMA public TO blogadmin;

    -- Grant CRUD on all current and future tables/sequences
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO blogadmin;
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO blogadmin;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO blogadmin;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO blogadmin;
EOSQL

echo "✅ User 'blogadmin' created with privileges on 'blog' database"

# Run the application schema (tables, indexes, etc.)
if [ -f /docker-entrypoint-initdb.d/schema.sql ]; then
    echo "📦 Applying database schema..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /docker-entrypoint-initdb.d/schema.sql
    echo "✅ Database schema applied successfully"
else
    echo "⚠️  schema.sql not found — skipping schema initialization"
fi
