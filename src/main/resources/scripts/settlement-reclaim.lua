-- 回收租約已到期的訂單:認領者可能已經消失,放回 queue 讓任一實例重新認領。
-- 每次回收計入重試次數,與結算例外共用上限,避免「認領 → 當掉 → 回收」無限循環。
-- 次數用完的放進待通知集合,由消費端通知成功後才移除,通知失敗不會讓訂單消失。
-- KEYS[1] = {order:settlement}:queue
-- KEYS[2] = {order:settlement}:processing
-- KEYS[3] = {order:settlement}:retries
-- KEYS[4] = {order:settlement}:abandoned
-- ARGV[1] = 現在時間 (epoch millis)
-- ARGV[2] = 重試次數上限
-- ARGV[3] = 本次最多回收幾筆
-- 回傳:本次次數用完、放進待通知集合的 orderId(僅供記錄)
local ids = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[3])
local abandoned = {}
for _, id in ipairs(ids) do
    local attempt = redis.call('HINCRBY', KEYS[3], id, 1)
    redis.call('ZREM', KEYS[2], id)
    if attempt > tonumber(ARGV[2]) then
        redis.call('HDEL', KEYS[3], id)
        redis.call('SADD', KEYS[4], id)
        table.insert(abandoned, id)
    else
        redis.call('ZADD', KEYS[1], ARGV[1], id)
    end
end
return abandoned
