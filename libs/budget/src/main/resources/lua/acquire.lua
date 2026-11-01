-- ARGV: 1=now 2=leaseId 3=requested 4=ttlMs   returns {granted, expiresAt}
local committed = reclaim()
local budget = tonumber(redis.call('HGET', bkey, 'budget') or '0')
local avail = math.max(0, budget - committed)
local g = math.min(tonumber(ARGV[3]), avail)
local exp = now + tonumber(ARGV[4])
if g > 0 then
  redis.call('HSET', lkey, ARGV[2], fmt(g) .. '|0|' .. fmt(exp))
  redis.call('HSET', bkey, 'committed', fmt(committed + g))
end
return { fmt(g), fmt(exp) }
