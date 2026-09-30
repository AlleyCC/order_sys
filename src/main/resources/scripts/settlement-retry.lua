-- 結算拋出例外後的重試:只有租約仍屬於自己時才處理,間隔依重試次數拉長。
-- 放回 queue 與移出 processing 在同一支腳本內完成,中間不會有「兩邊都不在」的時刻。
-- KEYS[1] = {order:settlement}:queue
-- KEYS[2] = {order:settlement}:processing
-- KEYS[3] = {order:settlement}:retries
-- KEYS[4] = {order:settlement}:abandoned
-- ARGV[1] = orderId
-- ARGV[2] = 認領時寫入的租約到期時間 (epoch millis)
-- ARGV[3] = 現在時間 (epoch millis)
-- ARGV[4..] = 第 1、2、3... 次重試前要等的毫秒數;個數即重試上限
-- 回傳:-1 = 租約已不屬於自己,未做任何事;0 = 次數用完,已放進待通知集合;n = 第 n 次重試
local lease = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not lease or tonumber(lease) ~= tonumber(ARGV[2]) then
    return -1
end

local maxRetries = #ARGV - 3
local attempt = redis.call('HINCRBY', KEYS[3], ARGV[1], 1)
redis.call('ZREM', KEYS[2], ARGV[1])
if attempt > maxRetries then
    redis.call('HDEL', KEYS[3], ARGV[1])
    redis.call('SADD', KEYS[4], ARGV[1])
    return 0
end
local dueAt = tonumber(ARGV[3]) + tonumber(ARGV[3 + attempt])
redis.call('ZADD', KEYS[1], string.format('%.0f', dueAt), ARGV[1])
return attempt
