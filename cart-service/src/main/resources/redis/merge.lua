-- KEYS[1] user cart, KEYS[2] guest cart; ARGV: now, user ttl
-- Sums quantities (capped), keeps the earlier added time, drops the newest guest lines beyond the
-- SKU limit, deletes the guest key. An unknown guest key leaves the user cart untouched.
local user, guest, now, ttl = KEYS[1], KEYS[2], ARGV[1], tonumber(ARGV[2])
if redis.call('EXISTS', guest) == 0 then
  return reply(user, 'OK', 0)
end
local lines = {}
local guest_fields = redis.call('HGETALL', guest)
for i = 1, #guest_fields, 2 do
  local field = guest_fields[i]
  if string.sub(field, 1, 2) == 'q:' then
    local sku = string.sub(field, 3)
    lines[#lines + 1] = {
      sku = sku,
      qty = tonumber(guest_fields[i + 1]),
      t = tonumber(redis.call('HGET', guest, 't:' .. sku)) or 0,
    }
  end
end
table.sort(lines, function(a, b)
  if a.t ~= b.t then
    return a.t < b.t
  end
  return a.sku < b.sku
end)
local total = count_skus(user)
local dropped = 0
for _, line in ipairs(lines) do
  local existing = tonumber(redis.call('HGET', user, 'q:' .. line.sku))
  if existing then
    redis.call('HSET', user, 'q:' .. line.sku, math.min(MAX_QTY, existing + line.qty))
    local ut = tonumber(redis.call('HGET', user, 't:' .. line.sku)) or line.t
    redis.call('HSET', user, 't:' .. line.sku, math.min(ut, line.t))
  elseif total >= MAX_SKUS then
    dropped = dropped + 1
  else
    redis.call('HSET', user, 'q:' .. line.sku, math.min(MAX_QTY, line.qty), 't:' .. line.sku, line.t)
    total = total + 1
  end
end
redis.call('DEL', guest)
touch(user, now, ttl)
return reply(user, 'OK', dropped)
