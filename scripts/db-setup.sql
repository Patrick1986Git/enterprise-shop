-- Optional manual bootstrap for local PostgreSQL development.
-- Prefer `docker compose run --rm --no-deps --build database-role-bootstrap` for Docker Compose.
-- Run as a PostgreSQL administrative user (for example: postgres).
-- Keep credentials aligned with local-only .env values; never commit real secrets.

SELECT 'CREATE DATABASE enterprise_shop_dev'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'enterprise_shop_dev')\gexec

\connect enterprise_shop_dev

SELECT format(
  'CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
  'shop_dev',
  'shop_dev'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_dev')\gexec

ALTER ROLE shop_dev WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD 'shop_dev';
GRANT CONNECT ON DATABASE enterprise_shop_dev TO shop_dev;
GRANT USAGE ON SCHEMA public TO shop_dev;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO shop_dev;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO shop_dev;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO shop_dev;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO shop_dev;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO shop_dev;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO shop_dev;
