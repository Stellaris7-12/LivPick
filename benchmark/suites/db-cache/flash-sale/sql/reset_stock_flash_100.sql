UPDATE tb_seckill_voucher
SET stock = 100,
    begin_time = NOW() - INTERVAL 1 HOUR,
    end_time = NOW() + INTERVAL 2 HOUR
WHERE voucher_id = 7;

DELETE FROM tb_voucher_order
WHERE voucher_id = 7;

ALTER TABLE tb_voucher_order AUTO_INCREMENT = 1011;
