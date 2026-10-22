-- Dev only: a separate database and role for Logto (throwaway password).
-- CREATEROLE: Logto's `db seed` creates per-tenant roles.
CREATE ROLE logto LOGIN CREATEROLE PASSWORD 'logto-dev';
CREATE DATABASE logto OWNER logto;
