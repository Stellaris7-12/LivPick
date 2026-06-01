DROP DATABASE IF EXISTS livpick_db_cache_mq;
CREATE DATABASE livpick_db_cache_mq DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE livpick_db_cache_mq;
SOURCE src/main/resources/db/hmdp2.sql;
USE livpick_db_cache_mq;
SOURCE benchmark-final/suites/db-cache-mq/standard/sql/patch_schema.sql;
