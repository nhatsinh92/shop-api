-- KEYS[1] cart key; ARGV: sku, now, ttl
local key, sku, now, ttl = KEYS[1], ARGV[1], ARGV[2], tonumber(ARGV[3])
if redis.call('HEXISTS', key, 'q:' .. sku) == 0 then
  return reply(key, 'ITEM_NOT_FOUND', 0)
end
redis.call('HDEL', key, 'q:' .. sku, 't:' .. sku)
touch(key, now, ttl)
return reply(key, 'OK', 0)
