-- 买单冻结资金：可用 -= amt，冻结 += amt。幂等：同一 orderId 只冻一次。
-- KEYS[1]=acct  KEYS[2]=marker(frz)
-- ARGV[1]=amount  ARGV[2]=markerTtlSec
-- return 1 成功 / 2 重复（已冻结过）/ -1 资金不足 / -2 未开户
if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
local cash = redis.call('HGET', KEYS[1], 'cash')
if not cash then return -2 end
local amt = tonumber(ARGV[1])
if tonumber(cash) < amt then return -1 end
redis.call('HINCRBY', KEYS[1], 'cash', -amt)
redis.call('HINCRBY', KEYS[1], 'frozen', amt)
redis.call('SET', KEYS[2], amt, 'EX', ARGV[2])
return 1
