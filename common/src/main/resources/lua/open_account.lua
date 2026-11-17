-- 开户：仅当不存在时初始化。KEYS[1]=acct ARGV[1]=initCash  return 1 新建 / 0 已存在
if redis.call('HSETNX', KEYS[1], 'cash', ARGV[1]) == 0 then return 0 end
redis.call('HSET', KEYS[1], 'frozen', 0, 'init', ARGV[1])
return 1
