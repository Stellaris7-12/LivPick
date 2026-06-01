SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'idx_status_create_time'
);

SET @ddl := IF(
    @idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD INDEX idx_status_create_time (status, create_time)',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
