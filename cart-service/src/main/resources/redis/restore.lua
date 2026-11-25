-- Writes one cart back from its snapshot, only when the key is missing (never overwrites).
-- KEYS[1] cart key; ARGV: ttl (seconds), then field / value pairs of the hash. Returns 1 when
-- written, 0 when the key already exists.
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('HSET', KEYS[1], unpack(ARGV, 2))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
return 1
