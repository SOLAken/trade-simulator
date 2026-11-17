-- T+1 惰性解锁：持仓的最近交易日早于今天时，把昨日及以前买入的 pending 转为可卖。
local function t1_roll(pos, today)
  local day = tonumber(redis.call('HGET', pos, 'day') or '0')
  if day < today then
    local pending = tonumber(redis.call('HGET', pos, 'pending') or '0')
    if pending > 0 then
      redis.call('HINCRBY', pos, 'avail', pending)
      redis.call('HSET', pos, 'pending', 0)
    end
    redis.call('HSET', pos, 'day', today)
  end
end
