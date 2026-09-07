-- Module 4 seat locking - compare-and-delete release (plan/redis.md section 5.2).
-- A bare DEL on a lock key is forbidden anywhere in this codebase: without the
-- comparison, a delayed release from a user whose lock already expired could
-- delete the NEXT user's legitimately acquired lock (see plan/redis.md section 3
-- for the exact race). GET-then-DEL is only safe because this script is atomic -
-- no other client can take the key between the comparison and the delete.
--
-- KEYS[1..n] : seat lock keys to release
-- ARGV[1]    : userId of the caller
-- Returns    : { <seatId>, ... } - the seats actually released (possibly empty)

local userId = ARGV[1]
local released = {}

for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == userId then
    redis.call('DEL', KEYS[i])
    table.insert(released, tonumber(KEYS[i]:match('([^:]+)$')))
  end
end

return released
