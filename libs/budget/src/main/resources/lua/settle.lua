-- ARGV: 1=now 2=leaseId 3=spentSoFar
local v = redis.call('HGET', lkey, ARGV[2])
local spent = tonumber(ARGV[3])
if v then
  local g, r, e = string.match(v, '^(%d+)|(%d+)|(%d+)$')
  local rep = math.max(tonumber(r), math.min(spent, tonumber(g)))
  redis.call('HSET', lkey, ARGV[2], g .. '|' .. fmt(rep) .. '|' .. e)
else
  late_spend(ARGV[2], spent, false)
end
return 1
