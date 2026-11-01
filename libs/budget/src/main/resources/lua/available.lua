-- returns {available, committed}
local committed = reclaim()
local budget = tonumber(redis.call('HGET', bkey, 'budget') or '0')
return { fmt(math.max(0, budget - committed)), fmt(committed) }
