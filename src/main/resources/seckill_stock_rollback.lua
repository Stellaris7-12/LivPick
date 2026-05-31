local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local reorderKey = 'seckill:reorder:' .. voucherId .. ':' .. userId

redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
redis.call('set', reorderKey, orderId)

return 0
