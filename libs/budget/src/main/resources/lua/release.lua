-- ARGV: 1=now 2=leaseId 3=spentTotal
local v = redis.call('HGET', lkey, ARGV[2])
local spent = tonumber(ARGV[3])
if v then
  local g, r, e = string.match(v, '^(%d+)|(%d+)|(%d+)$')
  g = tonumber(g); r = tonumber(r)
  local used = math.min(math.max(spent, r), g)
  redis.call('HSET', bkey, 'committed', fmt(committed_now() - (g - used)))
  redis.call('HDEL', lkey, ARGV[2])
else
  late_spend(ARGV[2], spent, true)
end
return 1
