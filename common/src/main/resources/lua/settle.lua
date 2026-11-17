-- 成交结算（一笔成交 + 可选的订单尾款释放），所有金额由调用方显式给出，脚本只做原子增量。
-- KEYS[1]=acct KEYS[2]=pos KEYS[3]=marker(stl) KEYS[4]=posset
-- ARGV[1]=side(1买 2卖) ARGV[2]=qty ARGV[3]=amount(成交额) ARGV[4]=fee(佣金+税)
-- ARGV[5]=release(订单终结时退回的剩余冻结：买=分，卖=股) ARGV[6]=tradingDay ARGV[7]=markerTtlSec ARGV[8]=symbol
-- return 1 成功 / 0 重复
--#include t1_roll
if redis.call('SET', KEYS[3], 1, 'NX', 'EX', ARGV[7]) == false then return 0 end
local side, qty, amount, fee, release = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])
t1_roll(KEYS[2], tonumber(ARGV[6]))
if side == 1 then
  -- 买：冻结资金扣除 成交额+佣金，股票进 pending（T+1 才可卖）
  redis.call('HINCRBY', KEYS[1], 'frozen', -(amount + fee) - release)
  redis.call('HINCRBY', KEYS[1], 'cash', release)
  redis.call('HINCRBY', KEYS[2], 'qty', qty)
  redis.call('HINCRBY', KEYS[2], 'pending', qty)
  redis.call('HINCRBY', KEYS[2], 'cost', amount + fee)
  redis.call('SADD', KEYS[4], ARGV[8])
else
  -- 卖：冻结持仓扣除，成本按移动加权平均等比例结转，资金到账 成交额-佣金-印花税
  local before = tonumber(redis.call('HGET', KEYS[2], 'qty') or '0')
  local cost = tonumber(redis.call('HGET', KEYS[2], 'cost') or '0')
  local costOut = 0
  if before > 0 then costOut = math.floor(cost * qty / before + 0.5) end
  redis.call('HINCRBY', KEYS[2], 'qty', -qty)
  redis.call('HINCRBY', KEYS[2], 'frozen', -qty - release)
  redis.call('HINCRBY', KEYS[2], 'avail', release)
  redis.call('HINCRBY', KEYS[2], 'cost', -costOut)
  redis.call('HINCRBY', KEYS[1], 'cash', amount - fee)
end
return 1
