-- Shared helpers, prepended to every cart script. Key layout: see plans/epics/cart.md.
local MAX_QTY = 10
local MAX_SKUS = 50

local function count_skus(key)
  local n = 0
  for _, field in ipairs(redis.call('HKEYS', key)) do
    if string.sub(field, 1, 2) == 'q:' then
      n = n + 1
    end
  end
  return n
end

-- Every write bumps the version, stamps the time and refreshes the TTL.
local function touch(key, now, ttl)
  redis.call('HINCRBY', key, 'version', 1)
  redis.call('HSET', key, 'updatedAt', now)
  redis.call('EXPIRE', key, ttl)
end

-- Reply: { status, droppedLines, field, value, field, value, ... } where the pairs are the whole
-- hash, read in the same script as the write so version and items always belong together.
local function reply(key, status, dropped)
  local out = { status, dropped }
  for _, v in ipairs(redis.call('HGETALL', key)) do
    out[#out + 1] = v
  end
  return out
end
