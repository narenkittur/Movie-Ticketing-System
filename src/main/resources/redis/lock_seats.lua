-- Module 4 seat locking - all-or-nothing acquire (plan/redis.md section 5.1).
-- Redis runs this atomically: no other client's command can interleave with it,
-- which is what lets us check every requested seat and then write every requested
-- seat with no window in between (a loop of per-seat SET NX from Java would leave a
-- "phantom-LOCKED" seat visible to everyone during rollback - see claude.md's
-- Redis locking standards for why that's banned).
--
-- KEYS[1..n] : seat_lock:<showId>:<seatId>, one per requested seat
-- ARGV[1]    : userId of the caller (the value written into each key)
-- ARGV[2]    : TTL in seconds (300)
--
-- Returns:
--   { -1, <seatId>, <seatId>, ... }  conflict - NOTHING was written
--   { <minRemainingTtlMillis> }      success  - every key is held by ARGV[1]

local userId = ARGV[1]
local ttl    = tonumber(ARGV[2])

-- Pass 1: inspect every key before writing any of them. Nothing in this loop
-- mutates, so bailing out here leaves the keyspace exactly as we found it.
-- Note the second condition: a key WE already hold is not a conflict.
local conflicts = {}
for i = 1, #KEYS do
  local holder = redis.call('GET', KEYS[i])
  if holder and holder ~= userId then
    -- the seat id is the last colon-delimited segment of the key
    table.insert(conflicts, tonumber(KEYS[i]:match('([^:]+)$')))
  end
end

if #conflicts > 0 then
  table.insert(conflicts, 1, -1)   -- sentinel: "this is a conflict list"
  return conflicts
end

-- Pass 2: only now do we mutate, and only via NX. A key we already hold is
-- left completely untouched, so it KEEPS ITS ORIGINAL TTL. That is what makes
-- a retry idempotent without quietly becoming the heartbeat claude.md bans.
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], userId, 'NX', 'EX', ttl)
end

-- The countdown the client shows must be the SOONEST expiry among the held
-- seats, not the newest - see endpoint #3's note in plan/redis.md section 4.
local minTtl = -1
for i = 1, #KEYS do
  local pttl = redis.call('PTTL', KEYS[i])
  if minTtl == -1 or pttl < minTtl then minTtl = pttl end
end

return { minTtl }
