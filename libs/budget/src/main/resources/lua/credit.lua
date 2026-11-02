-- ARGV: 1=now 2=amountToCredit   returns the amount actually credited (never dips below what open leases still hold)
reclaim()
local committed = committed_now()
local open = 0
local leases = redis.call('HGETALL', lkey)
for i = 2, #leases, 2 do
  local g = string.match(leases[i], '^(%d+)|')
  open = open + tonumber(g)
end
local amt = math.min(tonumber(ARGV[2]), math.max(0, committed - open))
redis.call('HSET', bkey, 'committed', fmt(committed - amt))
return fmt(amt)
