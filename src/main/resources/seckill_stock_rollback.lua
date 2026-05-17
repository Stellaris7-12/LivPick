-- 1. 参数列表
local voucherId = ARGV[1]

-- 2. 数据key
local stockKey = 'seckill:stock:' .. voucherId

-- 3. 仅回补库存，不恢复用户抢购资格
redis.call('incrby', stockKey, 1)

return 0
