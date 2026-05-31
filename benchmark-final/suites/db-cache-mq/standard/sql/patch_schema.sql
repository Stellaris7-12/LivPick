ALTER TABLE tb_voucher_order
    MODIFY COLUMN id bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'primary key';

ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_voucher_user (voucher_id, user_id);

ALTER TABLE tb_voucher_order
    ADD INDEX idx_status_create_time (status, create_time);
