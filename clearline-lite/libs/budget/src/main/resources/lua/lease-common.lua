-- KEYS: 1=budget hash b:{c}  2=open leases hash l:{c}  3=reclaimed tombstones r:{c}
-- ARGV[1] = now (ms, supplied by caller so the injectable clock works and the script stays deterministic)
local bkey, lkey, rkey = KEYS[1], KEYS[2], KEYS[3]
local now = tonumber(ARGV[1])

local function fmt(n) return string.format('%.0f', n) end

local function committed_now()
  return tonumber(redis.call('HGET', bkey, 'committed') or '0')
end

local function reclaim()
  local committed = committed_now()
  local leases = redis.call('HGETALL', lkey)
  for i = 1, #leases, 2 do
    local g, r, e = string.match(leases[i + 1], '^(%d+)|(%d+)|(%d+)$')
    if tonumber(e) <= now then
      committed = committed - (tonumber(g) - tonumber(r))
      redis.call('HDEL', lkey, leases[i])
      redis.call('HSET', rkey, leases[i], r)
      redis.call('EXPIRE', rkey, 86400)
    end
  end
  redis.call('HSET', bkey, 'committed', fmt(committed))
  return committed
end

local function late_spend(id, spent, closing)
  local tomb = redis.call('HGET', rkey, id)
  if not tomb then return end
  tomb = tonumber(tomb)
  local delta = math.max(0, spent - tomb)
  redis.call('HSET', bkey, 'committed', fmt(committed_now() + delta))
  if closing then
    redis.call('HDEL', rkey, id)
  else
    redis.call('HSET', rkey, id, fmt(math.max(tomb, spent)))
  end
end
