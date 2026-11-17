-- 撤单/过期：退回订单剩余冻结。幂等：同一 orderId 只退一次。
-- KEYS[1]=acct或pos  KEYS[2]=marker(rel)
-- ARGV[1]=side ARGV[2]=release ARGV[3]=markerTtlSec ARGV[4]=tradingDay
-- return 1 成功 / 0 重复
--#include t1_roll
if redis.call('SET', KEYS[2], 1, 'NX', 'EX', ARGV[3]) == false then return 0 end
local release = tonumber(ARGV[2])
if tonumber(ARGV[1]) == 1 then
  redis.call('HINCRBY', KEYS[1], 'frozen', -release)
  redis.call('HINCRBY', KEYS[1], 'cash', release)
else
  t1_roll(KEYS[1], tonumber(ARGV[4]))
  redis.call('HINCRBY', KEYS[1], 'frozen', -release)
  redis.call('HINCRBY', KEYS[1], 'avail', release)
end
return 1
