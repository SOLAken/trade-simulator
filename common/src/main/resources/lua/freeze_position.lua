-- 卖单冻结持仓：可卖 -= qty，冻结 += qty。
-- KEYS[1]=pos  KEYS[2]=marker(frz)
-- ARGV[1]=qty  ARGV[2]=markerTtlSec  ARGV[3]=tradingDay
-- return 1 成功 / 2 重复 / -1 可卖不足
--#include t1_roll
if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
t1_roll(KEYS[1], tonumber(ARGV[3]))
local avail = tonumber(redis.call('HGET', KEYS[1], 'avail') or '0')
local qty = tonumber(ARGV[1])
if avail < qty then return -1 end
redis.call('HINCRBY', KEYS[1], 'avail', -qty)
redis.call('HINCRBY', KEYS[1], 'frozen', qty)
redis.call('SET', KEYS[2], qty, 'EX', ARGV[2])
return 1
