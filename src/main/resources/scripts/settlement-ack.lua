-- 確認結算完成:只有租約仍屬於自己時才移出 processing。
-- 租約逾期後別的實例可能已重新認領並寫入新的租約;晚到的 ack 不能刪掉對方的紀錄,
-- 否則對方隨後當掉時,訂單會不在 queue 也不在 processing。
-- KEYS[1] = {order:settlement}:processing
-- KEYS[2] = {order:settlement}:retries
-- ARGV[1] = orderId
-- ARGV[2] = 認領時寫入的租約到期時間 (epoch millis)
-- 回傳:1 = 已確認;0 = 租約已不屬於自己,未做任何事
local lease = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not lease or tonumber(lease) ~= tonumber(ARGV[2]) then
    return 0
end
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1])
return 1
