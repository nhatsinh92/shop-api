-- KEYS[1] cart key; ARGV: sku, quantity (1-10), now, ttl
local key, sku, qty, now, ttl = KEYS[1], ARGV[1], tonumber(ARGV[2]), ARGV[3], tonumber(ARGV[4])
if redis.call('HEXISTS', key, 'q:' .. sku) == 0 then
  return reply(key, 'ITEM_NOT_FOUND', 0)
end
redis.call('HSET', key, 'q:' .. sku, qty)
touch(key, now, ttl)
return reply(key, 'OK', 0)
