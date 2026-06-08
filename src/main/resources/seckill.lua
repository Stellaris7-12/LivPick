local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local traceId = ARGV[4]
local ts = ARGV[5]
local traceTtlSeconds = tonumber(ARGV[6])

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local traceKey = 'seckill:trace:' .. voucherId

local currentStock = tonumber(redis.call('get', stockKey))
if (currentStock == nil or currentStock <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

local beforeQty = currentStock
local afterQty = currentStock - 1
redis.call('set', stockKey, afterQty)
redis.call('sadd', orderKey, userId)

local tracePayload = cjson.encode({
    traceId = tonumber(traceId),
    orderId = tonumber(orderId),
    userId = tonumber(userId),
    voucherId = tonumber(voucherId),
    logType = 'DEDUCT',
    beforeQty = beforeQty,
    changeQty = -1,
    afterQty = afterQty,
    ts = tonumber(ts)
})
redis.call('hset', traceKey, traceId, tracePayload)
if (traceTtlSeconds ~= nil and traceTtlSeconds > 0) then
    local currentTtl = redis.call('ttl', traceKey)
    if (currentTtl == nil or currentTtl < 0) then
        redis.call('expire', traceKey, traceTtlSeconds)
    end
end

return 0 - afterQty - 1
