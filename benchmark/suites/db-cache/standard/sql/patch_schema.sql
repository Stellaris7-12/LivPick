ALTER TABLE tb_voucher_order
    MODIFY COLUMN id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键';

ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_voucher_user (voucher_id, user_id);
