-- KEYS[1] cart key; ARGV: sku, quantity, now (epoch millis), ttl (seconds)
local key, sku, qty, now, ttl = KEYS[1], ARGV[1], tonumber(ARGV[2]), ARGV[3], tonumber(ARGV[4])
local current = tonumber(redis.call('HGET', key, 'q:' .. sku))
if current then
  if current + qty > MAX_QTY then
    return reply(key, 'QTY_LIMIT', 0)
  end
  redis.call('HSET', key, 'q:' .. sku, current + qty)
else
  if qty > MAX_QTY then
    return reply(key, 'QTY_LIMIT', 0)
  end
  if count_skus(key) >= MAX_SKUS then
    return reply(key, 'ITEMS_LIMIT', 0)
  end
  redis.call('HSET', key, 'q:' .. sku, qty, 't:' .. sku, now)
end
touch(key, now, ttl)
return reply(key, 'OK', 0)
