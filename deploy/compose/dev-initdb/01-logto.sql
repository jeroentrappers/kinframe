-- Dev only: a separate database and role for Logto (throwaway password).
CREATE ROLE logto LOGIN PASSWORD 'logto-dev';
CREATE DATABASE logto OWNER logto;
