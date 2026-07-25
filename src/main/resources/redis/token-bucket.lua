-- Token bucket rate limiter, atomic qua Lua script (tránh race giữa nhiều instance).
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (số token tối đa)
-- ARGV[2] = refill_tokens (số token cộng thêm mỗi refill_period_ms)
-- ARGV[3] = refill_period_ms
-- ARGV[4] = now_ms
-- Return: 1 nếu tiêu được 1 token, 0 nếu không còn token.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3])
local now_ms = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
  tokens = capacity
  last_refill = now_ms
end

local elapsed = now_ms - last_refill
if elapsed > 0 then
  local periods = math.floor(elapsed / refill_period_ms)
  if periods > 0 then
    tokens = math.min(capacity, tokens + periods * refill_tokens)
    last_refill = last_refill + periods * refill_period_ms
  end
end

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
-- TTL dọn bucket không hoạt động: 2 chu kỳ refill đầy từ rỗng, tối thiểu 60s.
local ttl_seconds = math.max(60, math.ceil((capacity * refill_period_ms * 2) / 1000))
redis.call('EXPIRE', key, ttl_seconds)

return allowed
