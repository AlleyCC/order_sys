-- 認領到期訂單:查詢與搬移在同一支腳本內完成,執行期間 Redis 不會插入其他命令。
-- KEYS[1] = {order:settlement}:queue       (score = 截止時間)
-- KEYS[2] = {order:settlement}:processing  (score = 租約到期時間)
-- ARGV[1] = 現在時間 (epoch millis)
-- ARGV[2] = 本次最多認領幾筆
-- ARGV[3] = 租約到期時間 (epoch millis)
-- 回傳:本次認領到的 orderId,依截止時間由早到晚
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
for _, id in ipairs(ids) do
    redis.call('ZREM', KEYS[1], id)
    redis.call('ZADD', KEYS[2], ARGV[3], id)
end
return ids
