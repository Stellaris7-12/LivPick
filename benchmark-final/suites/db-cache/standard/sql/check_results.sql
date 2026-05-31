SELECT stock
FROM tb_seckill_voucher
WHERE voucher_id = 7;

SELECT COUNT(*) AS order_count
FROM tb_voucher_order
WHERE voucher_id = 7;

SELECT user_id, COUNT(*) AS order_count
FROM tb_voucher_order
WHERE voucher_id = 7
GROUP BY user_id
HAVING COUNT(*) > 1;
