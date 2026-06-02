-- =============================================================================
-- MySQL init script (runs on first container start with empty data dir)
-- The root user is created automatically with the password from
-- MYSQL_ROOT_PASSWORD; this file just ensures utf8mb4 is the default charset.
-- =============================================================================

ALTER DATABASE ideaparty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
