-- Migration V3: Drop auth_sessions table and drop tokens_valid_after from users

DROP TABLE IF EXISTS auth_sessions CASCADE;

ALTER TABLE users DROP COLUMN IF EXISTS tokens_valid_after;
