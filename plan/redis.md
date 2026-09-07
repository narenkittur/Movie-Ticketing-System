# Module 4 — Redis Seat Locking

Status: **implemented.** All files in section 12 below exist in code. This document
remains the design of record; see `logic/redis-locking.md` for the implementation
rationale, including the handful of implementation-time decisions the design left
open (marked there, not here). It builds directly on the Module 3 seat catalog
(`plan/crud.md`), the `SeatLockView` seam it left behind, and the Module 2 security
chain (`plan/authentication.md`). Where it deviates from what an earlier doc
promised, that's called out explicitly rather than quietly changed.

**Two bookkeeping corrections this document makes:**

1. **This is Module 4, not Module 3.** Module 3 (Movie/Show/Seat CRUD) is already
   implemented. Numbering here follows `claude.md`.
2. `claude.md` previously cited a design doc at `plan/redis_locking.md`. **That file
   never existed** — this file (`plan/redis.md`) is the real one, and `claude.md` has
   been repointed at it.

**And one conflict it resolves:** `plan/authentication.md` §7 described the lock key
as `seat:{showId}:{seatId}`; `claude.md` mandates `seat_lock:{showId}:{seatId}`.
**`seat_lock:` wins** (see §3), and authentication.md has been corrected to match.

---

## 1. Goal & Scope

Module 3 can *describe* seats. It cannot *hold* one. Every seat the API reports today
is `AVAILABLE` or `BOOKED` — the `LOCKED` state that the entire architecture is built
around does not exist at runtime yet, because `NoOpSeatLockView` always answers
"nothing is locked." This module makes `LOCKED` real, and in doing so it becomes the
module that `claude.md`'s headline promise actually rests on: **two users must never
end up with the same seat.**

A "lock" here means one specific thing: **a short-lived, exclusive, self-expiring
claim on a seat, held by one authenticated user, that gives them a private window in
which to complete payment.** It is not a booking. It confers no durable rights. It
disappears on its own if the user walks away.

**Functional requirements**

- **Authenticated user**
  - Lock one or more seats of a show, **all-or-nothing** — either every requested seat
    is held by you when the call returns, or none of them are and nothing changed.
  - Release seats you hold, before paying, so an abandoned checkout frees inventory
    immediately instead of after the full TTL.
  - Ask which seats of a show *you* currently hold, and how long you have left.
- **Everyone (via Module 3's existing endpoint)**
  - `GET /shows/{showId}/seats` starts reporting a real `LOCKED` status, computed from
    Redis, without any change to its DTO or its contract.
- **Module 5 (booking), served by this module's service API**
  - Prove, *inside* the booking transaction, that the caller still holds every seat.
  - Release those locks *after* the transaction commits, never before.

**Non-functional requirements** (carried from `claude.md` "Project Constraints")

- Every public endpoint documented via OpenAPI/Swagger (`@Operation`,
  `@SecurityRequirement(name = "bearerAuth")` — the scheme `OpenApiConfig` defines).
- Every service method gets SLF4J logging, parameterised (`{}`), never concatenated.
- TTL is **300 seconds**, fixed.
- Fail closed. A seat is never assumed free because Redis didn't answer.

**Explicitly out of scope for Module 4:**

- **Lock extension / heartbeat / "you have 30 more seconds" — banned outright**
  (`claude.md`). If 300 s is the wrong number, change the number, not the mechanism.
  §2 explains why this ban is a feature.
- **Queuing or fair waiting.** A contested seat returns `409` immediately (principle
  #3, fail-fast). No first-come-first-served queue, no retry-after, no notification
  when a lock frees.
- **Redlock / multi-node Redis consensus.** One Redis node. See §9-F for why that's
  the right call here and what it would actually cost to change.
- **Booking, payment, seat `BOOKED` transitions.** All Module 5. This module never
  writes to the `seats` table at all — see §8.
- **Pushing lock changes to other viewers in real time.** Module 6 (WebSockets);
  §11.2 leaves it a seam, not a stub.

---

## 2. The idea, in plain terms

The problem is a classic check-then-act race. Two users load the same seat map, both
see `A1` free, both click it, both start paying. Whatever we do at *payment* time, one
of them has already spent thirty seconds believing they had that seat.

So the claim has to be staked at *selection* time, not payment time. That gives three
candidate homes for it, and the choice is the whole design:

| Where the hold lives | Why not |
|---|---|
| A JVM lock (`synchronized`, `ReentrantLock`) | Dies the moment there are two app instances. Locks nothing across a load balancer, which is exactly the deployment this project claims to support (stateless JWT auth, principle #5). |
| A DB column (`Seat.status = 'LOCKED'`) | **Has no TTL.** A user who closes their laptop mid-checkout leaves that seat `LOCKED` forever. You'd then need a scheduled sweeper job, a `locked_until` column, and a `locked_by` column — i.e. you'd rebuild Redis's expiry semantics, badly, inside your durable data. It also mixes an ephemeral, seconds-lived fact into the table that is supposed to hold only committed truth. |
| **A Redis key with an expiry** | Atomic, shared across instances, and — the entire point — **it cleans itself up.** |

> **This is `claude.md` principle #1 stated as a mechanism rather than a rule.** "Never
> infer real-time lock state from the DB" isn't stylistic. It's the observation that
> durable storage has no natural notion of "…and forget this in five minutes," and
> that bolting one on is where seat-locking systems rot.

**TTL as reconciliation.** The thing worth internalising: there is no cleanup code in
this module. None. If the app crashes holding 400 locks, nothing needs to run to
release them — 300 seconds later they're gone, because expiry is Redis's job and Redis
does it whether or not our JVM is alive. Every failure path in §9 ends at the same
place: *the lock expires and the seat frees itself.* That property is what pays for
the "no heartbeat, ever" ban — the instant you let a client extend a lock, you've
built a way for a stuck client to hold a seat indefinitely, and you're back to needing
a sweeper.

The cost is honest and worth stating: **a user who takes longer than 300 seconds to
pay loses their seats**, possibly to someone else, possibly mid-payment. Module 5 must
therefore re-verify the hold inside its transaction (§11.1) and fail the booking
cleanly rather than assuming the lock is still there. The alternative — extending
locks — trades that rare, well-defined, recoverable failure for an unbounded one.

---

## 3. Key schema, value, and TTL

```
Key:    seat_lock:<showId>:<seatId>      e.g.  seat_lock:12:101
Value:  <userId>                          e.g.  "7"   (the User.id of the holder)
TTL:    300 seconds
```

Three deliberate choices in there:

**The seat id, not the seat number.** `A1` is not unique across the system — every
show has an `A1`. `Seat.id` is the primary key and is what `SeatLockView` already
traffics in, so the key needs no lookup table to interpret.

**The value is the holder's user id**, and that is what makes safe release possible.
Without it, `DEL seat_lock:12:101` is unconditional, and there is a real sequence where
that deletes the *wrong person's* lock:

1. User 7 locks seat 101. TTL starts.
2. User 7's request is slow / the app pauses. 300 s pass. **The lock expires.**
3. User 9 locks seat 101. Legitimately theirs now.
4. User 7's delayed "release" arrives and runs `DEL seat_lock:12:101` — **deleting
   user 9's lock.** Seat 101 is now free while user 9 is paying for it.

Which is why release is *always* compare-and-delete (§5.2) and why `claude.md` forbids
a bare `DEL` on a lock key anywhere in the codebase. The value isn't bookkeeping; it's
the guard.

**The id, not the username.** Usernames are mutable in principle and longer; the id is
stable, small, and is what `Booking.user` will be resolved to anyway.

> **Redis Cluster aside — the braces are not decoration.** `claude.md` writes the key
> as `seat_lock:{showId}:{seatId}` using `{}` as placeholder notation, but in Redis
> Cluster `{...}` is *literal hash-tag syntax*: only the text inside the braces is
> hashed to choose a slot. This project runs a single Redis node, so keys are written
> literally as `seat_lock:12:101` with no braces. **But** if this ever moves to
> Cluster, the multi-key Lua scripts in §5 become illegal the moment two of a show's
> seats land in different slots — and the one-character fix is to write keys as
> `seat_lock:{12}:101`, so every seat of a show shares a slot by construction. Worth
> knowing before it's an outage rather than after.

**Why 300 s.** Long enough for a human to enter card details unhurried; short enough
that a walk-away costs the next customer five minutes, not an evening. It lives in
`application.properties` as `seatlock.ttl-seconds` (default `300`), following the
`jwt.access-token-expiration-ms` precedent, so it's tunable without a recompile — but
`claude.md` still treats 300 as the project's answer, and changing it means updating
`claude.md` too.

---

## 4. Endpoint Catalog

| # | Method & Path | Auth | Purpose | Success | Primary failure(s) |
|---|---|---|---|---|---|
| 1 | `POST /shows/{showId}/seats/lock` | any logged-in user | Acquire an all-or-nothing hold on 1–10 seats | `200 OK` + `SeatLockResponse` | `409` seat booked or held by someone else · `404` unknown show/seat · `400` validation · `503` Redis down |
| 2 | `DELETE /shows/{showId}/seats/lock` | any logged-in user | Release seats **you** hold | `200 OK` + `SeatLockReleaseResponse` | `404` unknown show · `503` Redis down |
| 3 | `GET /shows/{showId}/seats/locks/mine` | any logged-in user | Which seats of this show do I hold, and for how much longer? | `200 OK` + `SeatLockResponse` | `404` unknown show · `503` Redis down |

Endpoint #3 is an addition beyond the two `claude.md` originally specified. The reason
is concrete: after a page refresh the browser has no way to tell *its own* `LOCKED`
seats (which should render as selected, with a live countdown) from someone else's
(greyed out). Without #3 the checkout timer cannot survive a reload or a second tab.
It costs one `MGET` plus a pipelined `PTTL`, and §10 covers the security matcher it
requires — which is not optional, see the warning there.

**Path convention.** These sit under `/shows/{showId}/seats/...` alongside Module 3's
existing `GET /shows/{showId}/seats`, because a lock is meaningless without its show
and the show id is needed to build the key anyway. Nothing goes under `/admin/**` —
locking is the ordinary user action this whole system exists for.

### Request / response shapes

**#1 — acquire**

```jsonc
POST /shows/12/seats/lock
Authorization: Bearer <access token>

{ "seatIds": [101, 102] }        // 1..10 ids, must belong to show 12
```

```jsonc
200 OK
{
  "showId": 12,
  "seatIds": [101, 102],
  "expiresAt": "2026-08-12T14:32:10",
  "secondsRemaining": 300          // drives the client-side countdown
}
```

```jsonc
409 Conflict                        // the standard ErrorResponse shape
{
  "timestamp": "2026-08-12T14:27:10",
  "status": 409,
  "error": "Conflict",
  "message": "One or more seats are no longer available",
  "path": "/shows/12/seats/lock",
  "details": ["seat 102 is held by another user"]   // ← names the culprits
}
```

Populating `details` matters for usability: the client can deselect exactly the lost
seat and retry, instead of clearing the whole selection and guessing. It reuses the
`details` list `ErrorResponse` already has for Bean Validation failures.

**#2 — release**

```jsonc
DELETE /shows/12/seats/lock
{ "seatIds": [101, 102] }
```

```jsonc
200 OK
{ "showId": 12, "releasedSeatIds": [101], "releasedCount": 1 }
```

Note `101` came back but `102` didn't — meaning `102`'s lock had already expired, or
was never held by this caller. **That is a `200`, not an error.** An expired lock and a
never-held lock are indistinguishable from outside, and both mean the same thing to
the caller: *you don't hold it, and you don't need to do anything about it.* Release is
idempotent by design; calling it twice is harmless.

**#3 — my locks**

```jsonc
GET /shows/12/seats/locks/mine
200 OK
{ "showId": 12, "seatIds": [101, 102], "expiresAt": "...", "secondsRemaining": 247 }
```

`secondsRemaining` is the **minimum** across the held seats — the moment the user's
selection starts breaking up, which is the only deadline a checkout timer can honestly
display. `seatIds: []` with `secondsRemaining: 0` when nothing is held.

---

## 5. ⚠️ The two Lua scripts

This is the sharp end of the module. Everything else is plumbing; correctness lives
here. Redis runs a Lua script **atomically** — no other client's command can interleave
with it — which is what lets us check many keys and then write many keys with no
window in between.

> **Why not a loop of `SET NX EX` from Java?** Because it isn't all-or-nothing. Locking
> `[101, 102]` as two calls means: `101` succeeds, `102` is taken, so now you must roll
> back `101` — and for that whole round trip, seat `101` is **phantom-LOCKED**: shown
> as unavailable to everyone, held by nobody's actual booking. Under contention that
> window is where two users can end up alternately blocking each other while neither
> makes progress. `claude.md` mandates the script form for exactly this reason.

### 5.1 `lock_seats.lua` — all-or-nothing acquire

```lua
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
-- a retry idempotent without turning it into the heartbeat claude.md bans.
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], userId, 'NX', 'EX', ttl)
end

-- The countdown the client shows must be the SOONEST expiry among the held
-- seats, not the newest - see endpoint #3's note.
local minTtl = -1
for i = 1, #KEYS do
  local pttl = redis.call('PTTL', KEYS[i])
  if minTtl == -1 or pttl < minTtl then minTtl = pttl end
end

return { minTtl }
```

**The return contract, stated precisely**, because Java has to branch on it:
the reply is always a list of integers. If `list[0] == -1`, the remaining elements are
the seat ids that blocked the request and **no key was written**. Otherwise the list
has exactly one element: the minimum remaining TTL in milliseconds.

Two subtleties worth not rediscovering the hard way:

- **`tonumber(...)` on the extracted seat id is load-bearing.** Redis converts a Lua
  table to a multi-bulk reply preserving types, so without it Java receives a mix of
  `Long` and `String` in one `List` and the parse gets ugly. Keeping every element
  numeric keeps the Java side a clean `List<Long>`.
- **Never put a `nil` in a returned Lua table** — Redis truncates the reply at the
  first `nil`. `table.insert` on a sequential list avoids this; assigning by index
  invites it.

### 5.2 `unlock_seats.lua` — compare-and-delete release

```lua
-- KEYS[1..n] : seat lock keys to release
-- ARGV[1]    : userId of the caller
-- Returns    : { <seatId>, ... } - the seats actually released (possibly empty)

local userId = ARGV[1]
local released = {}

for i = 1, #KEYS do
  -- GET-then-DEL is only safe because the script is atomic: no other client can
  -- take this key between the comparison and the delete. Doing the same thing
  -- as two round trips from Java would reintroduce the exact race in section 3.
  if redis.call('GET', KEYS[i]) == userId then
    redis.call('DEL', KEYS[i])
    table.insert(released, tonumber(KEYS[i]:match('([^:]+)$')))
  end
end

return released
```

Keys the caller doesn't own are silently skipped — that's the idempotence described in
§4 and it's also the safety property: **this script physically cannot delete another
user's lock.**

### 5.3 Wiring the scripts into Spring

`config/RedisConfig.java` (a new class — the project's first Redis `@Bean`s) loads each
file once at startup:

```java
@Bean
public DefaultRedisScript<List> lockSeatsScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("redis/lock_seats.lua"));
    script.setResultType(List.class);
    return script;
}
```

`RedisTemplate.execute(script, keys, args)` sends `EVALSHA` and only falls back to
shipping the whole script body if Redis doesn't know that SHA yet — so the script text
crosses the wire approximately once per Redis restart, not once per booking.

`StringRedisTemplate` (auto-configured by Spring Boot; no bean needed) is the template
to use. Deliberately **not** `RedisTemplate<Object,Object>` with a JSON serialiser:
values here are a bare user id, and `GenericJackson2JsonRedisSerializer` is a
*Jackson 2* class while this project runs Jackson 3 (`tools.jackson.*` — see
`claude.md`). Plain strings sidestep that mismatch entirely and keep keys readable in
`redis-cli`, which matters more than it sounds when debugging a lock that "should have
expired."

> **Redis version note.** `PTTL` inside a script is non-deterministic, which mattered on
> Redis < 5 (scripts were replicated verbatim and needed `replicate_commands()`).
> Redis 5+ replicates *effects*, so this is a non-issue — and §13 pins `redis:7-alpine`.

---

## 6. Step-by-step service flow

### 6.1 Acquire — `SeatLockService.lock(showId, request, username)`

```mermaid
flowchart TD
    A["POST /shows/12/seats/lock<br/>{seatIds:[101,102]}"] --> B{"@Valid<br/>1..10 ids, non-null"}
    B -->|fails| B1["400 Validation failed"]
    B --> C["resolve caller:<br/>Authentication.getName() → userId"]
    C --> D{"show 12 exists?"}
    D -->|no| D1["404 ShowNotFoundException"]
    D --> E{"all seat ids belong<br/>to show 12?"}
    E -->|no| E1["404 SeatNotFoundException"]
    E --> F{"any seat already<br/>BOOKED in the DB?"}
    F -->|yes| F1["409 SeatUnavailableException"]
    F --> G["EVALSHA lock_seats.lua<br/>KEYS=[seat_lock:12:101, seat_lock:12:102]<br/>ARGV=[7, 300]"]
    G -->|"Redis unreachable<br/>or times out"| G1["503 SeatLockUnavailableException"]
    G -->|"{-1, 102}"| G2["409 SeatUnavailableException<br/>details: seat 102"]
    G -->|"{299994}"| H["200 SeatLockResponse<br/>expiresAt, secondsRemaining"]
```

Steps in the service (`SeatLockService`):

1. **Resolve the caller's id.** `authentication.getName()` gives the username (the JWT's
   `sub` — `JwtAuthenticationFilter` puts a raw `String` principal in the context, not
   a `UserDetails`), then `userRepository.findByUsername(...)`. The id is **never**
   read from the request body — `plan/authentication.md` §7 pins this, and the reason
   is blunt: a client-supplied user id would let anyone release anyone's locks.
2. **Show exists?** `showRepository.existsById(showId)` → `ShowNotFoundException` (404).
   Reuses the Module 3 exception and its existing handler.
3. **Do these seats belong to this show?** `seatRepository.findByIdInAndShowId(seatIds,
   showId)` — a new derived-query method. If the returned count ≠ the distinct
   requested count, at least one id is bogus or belongs to a different show →
   `SeatNotFoundException` (404). **Skipping this check would let a caller lock
   `seat_lock:12:<some other show's seat>`** — a key nobody ever reads, so the lock
   silently does nothing and the user believes they have a seat.
4. **Any already `BOOKED`?** Straight from the rows fetched in step 3 → `409` naming
   them. This is a DB-truth fast path, deliberately *before* touching Redis: there's no
   point taking an ephemeral hold on a seat that is permanently sold.
5. **Run the script** (§5.1) with one key per seat and `[userId, ttlSeconds]`.
6. **Branch on the return.** `list[0] == -1` → `SeatUnavailableException` carrying the
   conflicting ids (they become the `409`'s `details`). Otherwise build the response:
   `secondsRemaining = minTtlMillis / 1000`, `expiresAt = now + secondsRemaining`.
7. Log `INFO "user {} locked seats {} on show {} for {}s"` on success,
   `WARN "user {} was denied seats {} on show {} (held by others)"` on conflict.

> **The step 4 → step 5 TOCTOU, acknowledged not hidden.** A seat can be booked by
> someone else in the microseconds between the DB read and the script. The result is a
> lock on an already-booked seat — harmless (Module 5's in-transaction re-check refuses
> the booking, and the stray lock expires in 300 s) and rare. Closing it properly would
> mean putting booked-ness in Redis too, i.e. two sources of truth for durable state,
> which principle #1 exists to prevent. This is the same "cheap pre-check plus an
> authoritative guard behind it" shape as `existsByShowId` + `uk_seat_show_seatnumber`
> in Module 3, and `existsByUsername` + the `users` UNIQUE constraint in Module 2.

### 6.2 Release — `SeatLockService.release(showId, request, username)`

Resolve the caller's id → verify the show exists → run `unlock_seats.lua` → return the
ids it actually deleted. **No ownership pre-check in Java**, because the script *is* the
ownership check and doing it in two steps would reintroduce the §3 race. No `404` for
unknown seat ids either: releasing a lock you don't hold is a no-op by definition, so
the shape of the id doesn't matter. Log `INFO "user {} released {} of {} requested
seats on show {}"`.

### 6.3 My locks — `SeatLockService.myLocks(showId, username)`

1. Resolve caller id; verify show exists.
2. `seatRepository.findByShowId(showId)` → the candidate seat ids.
3. One `MGET` over `seat_lock:<showId>:<seatId>` for all of them; keep the ids whose
   value equals the caller's id.
4. `PTTL` on just those keys via `executePipelined` — one round trip, not one per seat
   — and take the minimum.

No Lua needed: this reads, it never mutates, so atomicity buys nothing. A key expiring
between the `MGET` and the `PTTL` yields a negative TTL, which is floored to `0` and
simply drops out of the response — the honest answer.

---

## 7. ⚠️ Trace of one data object, end to end

Following a single payload from the wire to Redis and back, then out through the rest
of its life. This is the section to read if you read only one.

**Setup:** user `alice` (`User.id = 7`) is logged in and holds a valid access token.
Show `12` exists with 120 seats; `101` = `A1` and `102` = `A2`, both `AVAILABLE` in
the `seats` table.

### Stage 0 — the wire

```http
POST /shows/12/seats/lock HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json

{ "seatIds": [101, 102] }
```

### Stage 1 — security filter chain (Module 2, unchanged)

`JwtAuthenticationFilter` parses the token, validates its signature and expiry, and
calls `SecurityContextHolder.getContext().setAuthentication(...)` with principal
`"alice"` and authority `ROLE_USER`. `SecurityConfig`'s rules are evaluated: this is a
`POST`, so the `permitAll` GET rule for `/shows/**` does **not** match, and it falls
through to `.anyRequest().authenticated()` — satisfied. *If the token were missing,
`RestAuthenticationEntryPoint` would return a `401` `ErrorResponse` here and the
controller would never run.*

### Stage 2 — binding and validation

Jackson deserialises the body into `SeatLockRequest`. `@Valid` fires:
`@NotEmpty` (at least one seat) and `@Size(max = 10)` (no locking a whole screen) both
pass. A failure here would be thrown as `MethodArgumentNotValidException` and converted
by the **existing** `GlobalExceptionHandler.handleValidation` into a `400` listing each
bad field — no new code involved.

At this instant the object is: `SeatLockRequest{ seatIds = [101L, 102L] }`.

### Stage 3 — controller

```java
@PostMapping("/shows/{showId}/seats/lock")
public ResponseEntity<SeatLockResponse> lock(@PathVariable Long showId,
                                             @Valid @RequestBody SeatLockRequest request,
                                             Authentication authentication) {
    return ResponseEntity.ok(seatLockService.lock(showId, request, authentication.getName()));
}
```

Thin, per principle #4 — it extracts the username and delegates. **This is the first
place in the entire codebase that reads the caller's identity**; nothing before Module
4 ever needed it.

### Stage 4 — service: identity and validation

- `userRepository.findByUsername("alice")` → `User{id=7}`. Now the object has an owner:
  `(showId=12, seatIds=[101,102], userId=7)`.
- `showRepository.existsById(12)` → true.
- `seatRepository.findByIdInAndShowId([101,102], 12)` → 2 rows, both `status =
  "AVAILABLE"`. Count matches, none booked. Cleared.

### Stage 5 — the object becomes Redis keys

The domain object is now translated into the only form Redis understands:

```
KEYS = [ "seat_lock:12:101", "seat_lock:12:102" ]
ARGV = [ "7", "300" ]
EVALSHA <sha of lock_seats.lua> 2 seat_lock:12:101 seat_lock:12:102 7 300
```

Redis keyspace **before**:

```
(nothing matching seat_lock:12:*)
```

The script runs atomically: pass 1 finds both keys absent → no conflicts; pass 2 `SET`s
both with `NX EX 300`; then `PTTL` on each yields `300000` and `299999`, minimum
`299999`. Returns `{ 299999 }`.

Redis keyspace **after**:

```
seat_lock:12:101 = "7"   (ttl 300)
seat_lock:12:102 = "7"   (ttl 300)
```

**The `seats` table has not been touched and will not be.** Rows 101 and 102 still say
`AVAILABLE`. That divergence is not a bug — it *is* the architecture (§8).

### Stage 6 — back out as a response

```java
SeatLockResponse.builder()
    .showId(12L)
    .seatIds(List.of(101L, 102L))
    .expiresAt(LocalDateTime.now().plusSeconds(299))
    .secondsRemaining(299)
    .build();
```

```jsonc
200 OK
{ "showId": 12, "seatIds": [101,102],
  "expiresAt": "2026-08-12T14:32:09", "secondsRemaining": 299 }
```

Alice's browser starts a 299-second countdown.

### Stage 7 — what the *next* request sees

Bob, elsewhere, calls `GET /shows/12/seats` (public, unchanged since Module 3):

1. `SeatService.getSeatLayout(12)` loads all 120 `Seat` rows from MySQL. Rows 101/102
   read `AVAILABLE` — the durable truth.
2. It calls `seatLockView.lockedSeatIds(12, <the 120 ids it just loaded>)`.
3. `RedisSeatLockView` issues **one `MGET`** over 120 keys. 118 come back `nil`; two
   come back `"7"`. It returns `{101, 102}`.
4. `SeatService.toSeatDto` — **unchanged Module 3 code** — applies its precedence rule:
   not `BOOKED`, but present in the locked set → effective status `LOCKED`.

Bob sees `A1` and `A2` greyed out. Meanwhile `SELECT status FROM seats WHERE id = 101`
still returns `AVAILABLE`. **Two different answers, both correct, from two different
sources of truth — durable in MySQL, ephemeral in Redis.** If Bob tries to lock 102 he
gets a `409` whose `details` name seat 102, from the script's pass 1.

If Alice reloads her page, `GET /shows/12/seats/locks/mine` tells her browser that
101/102 are hers with 247 s left, and the countdown resumes — the reason endpoint #3
exists.

### Stage 8 — the three ways this ends

| Path | What happens | Who runs it |
|---|---|---|
| **Booked** | Module 5 opens a transaction → calls `assertHoldsAll(12, [101,102], 7)` → creates `Booking` + `BookingSeat` rows → sets both seats `BOOKED` → **commits** → *then* releases the locks (§11.1). The keys vanish; the DB rows now say `BOOKED`, which outranks `LOCKED` in `toSeatDto` forever after. | Module 5 |
| **Cancelled** | Alice clicks away. `DELETE /shows/12/seats/lock` runs `unlock_seats.lua`, both values equal `"7"`, both keys deleted. Seats are instantly free for Bob. | This module |
| **Abandoned** | Alice closes the laptop. **No code runs at all.** 300 s after stage 5, Redis evicts both keys on its own. The next `MGET` returns `nil`, `toSeatDto` reports `AVAILABLE`, and the seat is free. | Nobody |

That third row is the design's whole thesis. The most common failure mode in a
ticketing system — a human who wandered off — is handled by *writing no code*.

---

## 8. The `SeatLockView` seam ⚠️ (and a promise being knowingly broken)

Module 3 left this interface behind:

```java
public interface SeatLockView {
    Set<Long> lockedSeatIds(Long showId);
}
```

with `NoOpSeatLockView` returning `Collections.emptySet()`. Module 4's job is to
implement it for real. **`NoOpSeatLockView` is deleted, not kept alongside** — two
unqualified beans of one interface make Spring fail to autowire `SeatService`, and
`claude.md` calls for deletion explicitly.

### The signature changes

```java
Set<Long> lockedSeatIds(Long showId, Collection<Long> candidateSeatIds);
```

`plan/crud.md` §6 and `logic/listing-deep-dive.md` §2.7 both promised that
`SeatService` would need **zero** changes when Module 4 landed. That promise is being
traded for one line, deliberately, and here is the reasoning in full because reversing
it later should require reading it.

The single-argument signature forces the implementation to answer "which seats of this
show are locked?" *without being told which seats exist*. Redis has exactly one way to
do that: `SCAN MATCH seat_lock:12:*`. And `SCAN` is **O(the entire keyspace)** — it
walks every key in Redis regardless of how few belong to show 12. On a single-threaded
server, on the single most-requested endpoint in the application (the seat map a
frontend polls every few seconds), that cost grows with total system load and is paid
by every other command in the process.

| Approach | Redis work per seat-map request | Exact? | Code cost |
|---|---|---|---|
| `SCAN MATCH seat_lock:12:*` | O(all keys in Redis) | yes | zero |
| Secondary `SET` index per show | O(locked seats) + a reconciliation problem | **no** — the index doesn't expire with the keys, so it needs its own sweeper, re-inventing exactly what §2 says not to build | high |
| **`MGET` over known seat ids** | **O(seats in this show)**, one round trip | yes | **one line** |

`SeatService.getSeatLayout` has already loaded every `Seat` for the show one statement
earlier. It knows the ids. Withholding them from the collaborator that needs them, to
preserve the letter of a promise, buys nothing.

**What the seam actually had to protect, and still does:** no DTO changes, no
controller changes, and — most importantly — no change to `toSeatDto`'s precedence
logic, the one place principle #1 could be violated by accident. Those all hold. The
diff in `SeatService` is:

```java
// before
Set<Long> lockedSeatIds = seatLockView.lockedSeatIds(showId);

// after
Set<Long> lockedSeatIds = seatLockView.lockedSeatIds(
        showId, seats.stream().map(Seat::getId).toList());
```

`generateLayout`'s other call site is unaffected: it passes `Set.of()` directly to
`toSeatDto`, never consulting the seam, because a seat created a millisecond ago cannot
be locked.

### `RedisSeatLockView`

```java
@Component
public class RedisSeatLockView implements SeatLockView {
    @Override
    public Set<Long> lockedSeatIds(Long showId, Collection<Long> candidateSeatIds) {
        if (candidateSeatIds.isEmpty()) return Set.of();
        List<Long> ids = List.copyOf(candidateSeatIds);
        List<String> keys = ids.stream().map(id -> SeatLockKeys.key(showId, id)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);   // one MGET
        // index-aligned: values.get(i) is the holder of ids.get(i), or null if free.
        // Note: we do NOT compare to the caller here - ANY holder means LOCKED to
        // everyone. "is it mine?" is endpoint #3's question, not this one.
        ...
    }
}
```

`SeatLockKeys` is a tiny final class holding the prefix and the `key(showId, seatId)` /
`seatIdFrom(key)` helpers, shared by `RedisSeatLockView`, `SeatLockService`, and the
tests, so the key format is written down exactly once.

**And this path fails closed too.** If the `MGET` throws, `GET /shows/{id}/seats`
returns `503` rather than a map claiming locked seats are `AVAILABLE`. `claude.md`
only mandated fail-closed for the *lock* endpoints, but this endpoint now depends on
Redis as well, and the alternative is a map that lies — every user clicks a
"free" seat and collects a `409`. A five-second outage that reads as "service
briefly unavailable" is better than one that reads as "the app is broken." See §9-A
for the counter-argument, which is not unreasonable.

---

## 9. Failure modes

**A. Redis is unreachable / the command times out → `503` everywhere.**
Lettuce throws `RedisConnectionFailureException` or `QueryTimeoutException`; the service
wraps these in `SeatLockUnavailableException` → `503` via a new handler. Never "assume
unlocked" — that's the one assumption that can double-book a seat.

> ⚠️ **Lettuce's default command timeout is 60 seconds, and that default will hurt.**
> A hung (not crashed) Redis means every request thread blocks for a full minute; with
> Tomcat's 200-thread pool, the *whole application* stops serving anything — including
> endpoints with no Redis involvement — long before anyone sees a `503`. `spring.data.
> redis.timeout=1000ms` and `connect-timeout=1000ms` are what make "fail fast" true
> rather than aspirational. This is the single most important line of configuration in
> the module and it is easy to never notice is missing.

> **The counter-argument to `503` on the seat map**, for the record: the lock acquire is
> the real gate, so serving a stale map during an outage cannot cause a double booking
> — only confusion. Someone could reasonably prefer "browsing keeps working, clicks
> fail." If that's ever wanted, it's a `try/catch` around one call in
> `RedisSeatLockView` plus a flag in `SeatLayoutResponse` so the client can show a
> banner. Don't do it silently — a map that lies without saying so is worse than either
> option.

**B. TTL expires mid-checkout.** Alice's 300 s run out while the payment screen is up.
Her locks are gone; Bob may take the seats. When she submits, Module 5's
`assertHoldsAll` (§11.1) fails inside the transaction and returns a clean `409`. No
partial booking, no corrupted state — the transaction never commits. This is the
accepted cost of the no-heartbeat rule (§2), and the client should surface the
countdown prominently enough that it rarely bites.

**C. The same user locks the same seat twice.** Pass 1 sees a holder equal to the
caller and doesn't count it as a conflict; pass 2's `NX` leaves the existing key
untouched. Result: success, **original TTL preserved**. Retries and double-clicks are
safe, and the ban on lock extension survives — this is precisely why pass 2 uses `NX`
rather than a plain `SET`.

**D. Release arrives after expiry, seat retaken.** Covered in §3: the script's `GET`
returns the *new* holder's id, the comparison fails, nothing is deleted. This is the
race that makes a bare `DEL` unsafe and it is the reason `claude.md` forbids one.

**E. App crashes holding hundreds of locks.** Nothing runs. Every key expires within
300 s. No sweeper, no reconciliation job, no startup cleanup. §2's thesis.

**F. Redis itself dies and restarts empty.** All locks vanish at once — the failure
mode is "several users lose their holds simultaneously," recovered by re-locking, and
`assertHoldsAll` prevents any of them from booking on a phantom hold. No persistence is
configured (§13) and that's intentional: an `AOF`-replayed lock is a lock whose TTL is
already wrong. *Redlock across multiple nodes* would harden this and is deliberately
out of scope — it needs 3–5 independent nodes and buys protection against a failure
mode (a single node dying mid-checkout) whose worst outcome here is already just "some
users re-select their seats."

**G. Clock skew.** Not a factor. TTLs are enforced by Redis's own clock and durations
are relative; `expiresAt` is computed on the app server purely for display. Nothing
compares timestamps across machines.

**H. A user hoards seats.** `@Size(max = 10)` caps one request, but nothing stops ten
sequential requests. Deliberately unaddressed: rate limiting is a cross-cutting concern
and the 300 s TTL bounds the damage. Noted in §15-D.

---

## 10. Security wiring

**Identity comes from the token, never the body.** Every method takes the username from
`Authentication` and resolves it through `UserRepository`. There is no `userId` field
in `SeatLockRequest` and there must never be — it would let any caller release any
other user's locks by guessing a small integer.

> ⚠️ **The matcher ordering trap for endpoint #3.** `SecurityConfig` currently has:
> ```java
> .requestMatchers(HttpMethod.GET, "/movies", "/movies/**", "/shows/**").permitAll()
> ```
> `GET /shows/12/seats/locks/mine` matches `/shows/**`. Added naively, **endpoint #3
> would be public** — and since it answers "what does *this caller* hold," a public
> version leaks one user's selections to anyone who asks. Spring Security evaluates
> `authorizeHttpRequests` rules in declaration order and stops at the first match, so
> the fix is placement, not just presence:
> ```java
> // MUST come BEFORE the permitAll GET rule below
> .requestMatchers(HttpMethod.GET, "/shows/*/seats/locks/mine").authenticated()
> .requestMatchers(HttpMethod.GET, "/movies", "/movies/**", "/shows/**").permitAll()
> ```
> (`*` matches exactly one path segment, `**` matches many — `/shows/*/seats/...` is
> the right granularity here.)

Endpoints #1 and #2 need **no** config change: the public rule is `GET`-only, so `POST`
and `DELETE` under `/shows/**` already fall through to `.anyRequest().authenticated()`.
That GET-scoping was a deliberate Module 3 decision and it pays off here.

No `@PreAuthorize` on these methods — any authenticated user may lock a seat; there's
no role distinction. (Method security *is* now enforced app-wide, since Module 3 added
the `@EnableMethodSecurity` that Module 2 had assumed but never wired.)

**OpenAPI:** all three endpoints get `@Operation(summary = ...,
security = @SecurityRequirement(name = "bearerAuth"))` plus `@ApiResponse` entries for
`409` and `503`, matching how `SeatController` documents its endpoints.

---

## 11. Handoffs to Modules 5 and 6

### 11.1 Module 5 (booking) — two methods, so the ordering can't be got wrong

`claude.md` fixes the sequence: **verify ownership inside the transaction → commit →
release after commit.** Rather than leave Module 5 to remember that, this module ships
the two operations as service methods with the ordering built in:

```java
/** Throws SeatLockExpiredException (409) unless EVERY seat is currently held by userId.
 *  Call this INSIDE the @Transactional booking method, after re-reading the seat rows. */
public void assertHoldsAll(Long showId, Collection<Long> seatIds, Long userId);

/** Registers an afterCommit callback that releases the locks. Safe to call inside the
 *  transaction: if it rolls back, the callback never fires and the locks stand until
 *  their TTL - which is exactly right, since the user may retry payment. */
public void releaseAfterCommit(Long showId, Collection<Long> seatIds, Long userId);
```

`releaseAfterCommit` uses
`TransactionSynchronizationManager.registerSynchronization(...)` with an `afterCommit`
hook. **Why the ordering is not negotiable:** release the lock *before* the commit and
there is a window where the seat is unlocked in Redis but not yet `BOOKED` in MySQL —
another user can lock it, start paying, and then discover it was sold. Releasing after
commit means the seat goes straight from `LOCKED` to `BOOKED` with no gap; `toSeatDto`
ranks `BOOKED` above `LOCKED`, so the transition is invisible to clients.

Module 5 must **also** re-read the seat rows inside its transaction and reject anything
already `BOOKED`. The lock is the primary gate; the DB check is the backstop. Neither
replaces the other (`claude.md`).

### 11.2 Module 6 (WebSockets) — publish the events now, listen later

When a lock is acquired or released, this module publishes a Spring application event:

```java
eventPublisher.publishEvent(new SeatLockChangedEvent(showId, seatIds, LOCKED_or_RELEASED));
```

`ApplicationEventPublisher` is core Spring — **no new dependency**, no WebSocket code,
and today nothing listens. Module 6 adds a `@EventListener` that fans out to
subscribers, and this module doesn't change at all. Same seam philosophy Module 3 used
for `SeatLockView`, and it costs about six lines.

> ⚠️ **The honest caveat, stated now so Module 6 doesn't discover it late:** a lock that
> ends by **TTL expiry produces no event**, because no code runs — that's §2's whole
> point, and it's in direct tension with push notifications. Module 6 will need either
> Redis keyspace notifications (`notify-keyspace-events Kx`, an `expired` subscriber) or
> a client-side countdown that re-fetches the map when it hits zero. The second is
> simpler and probably right for this project. Either way it's Module 6's decision, and
> nothing here forecloses it.

---

## 12. File-by-file breakdown

```
src/main/java/com/example/movieticket/
├── config/
│   └── RedisConfig.java                  # NEW  two DefaultRedisScript<List> beans (§5.3)
├── controller/
│   └── SeatLockController.java           # NEW  the 3 endpoints, thin, OpenAPI-annotated
├── dto/
│   ├── SeatLockRequest.java              # NEW  @NotEmpty @Size(max=10) List<Long> seatIds
│   ├── SeatLockResponse.java             # NEW  showId, seatIds, expiresAt, secondsRemaining
│   └── SeatLockReleaseResponse.java      # NEW  showId, releasedSeatIds, releasedCount
├── exception/
│   ├── SeatNotFoundException.java        # NEW  404 - seat id unknown or not in this show
│   ├── SeatUnavailableException.java     # NEW  409 - booked, or held by someone else
│   ├── SeatLockExpiredException.java     # NEW  409 - Module 5's assertHoldsAll failed
│   ├── SeatLockUnavailableException.java # NEW  503 - fail-closed (§9-A)
│   └── GlobalExceptionHandler.java       # EDIT +4 handlers, + Redis exceptions → 503
├── repository/
│   └── SeatRepository.java               # EDIT + findByIdInAndShowId(Collection<Long>, Long)
├── security/
│   └── SecurityConfig.java               # EDIT the ordered matcher for endpoint #3 (§10)
└── service/
    ├── SeatLockKeys.java                 # NEW  the key format, written down once
    ├── SeatLockService.java              # NEW  lock / release / myLocks + Module-5 hooks
    ├── SeatLockView.java                 # EDIT signature gains candidateSeatIds (§8)
    ├── NoOpSeatLockView.java             # DELETE
    ├── RedisSeatLockView.java            # NEW  the MGET implementation
    └── SeatService.java                  # EDIT one line, in getSeatLayout (§8)

src/main/resources/
├── redis/lock_seats.lua                  # NEW  (§5.1)
├── redis/unlock_seats.lua                # NEW  (§5.2)
└── application.properties                # EDIT timeouts + seatlock.ttl-seconds

docker-compose.yml                        # NEW  Redis + MySQL (§13)
```

**Suggested build order** — each step leaves the app compiling and runnable:

1. `docker-compose.yml` first. Nothing below can be tested without a live Redis, and
   `MovieticketApplicationTests.contextLoads` has been failing for two modules purely
   for want of a MySQL to talk to.
2. `SeatLockKeys`, the two `.lua` files, `RedisConfig`, and the properties.
3. `SeatLockService.lock` + the exceptions + the handlers. Test with `redis-cli
   MONITOR` open beside you — watching the `EVALSHA` land is the fastest way to confirm
   the wiring.
4. `SeatLockController` endpoints #1 and #2.
5. The seam swap: edit `SeatLockView`, add `RedisSeatLockView`, delete
   `NoOpSeatLockView`, change the one line in `SeatService`. **Do these four together**
   — the context won't start with a half-swapped interface.
6. Endpoint #3 **and its SecurityConfig matcher in the same commit** (§10's trap).
7. `assertHoldsAll` / `releaseAfterCommit` — unused until Module 5, but writing them
   here is what keeps the ordering rule with the code that enforces it.
8. Tests (§14).

---

## 13. `docker-compose.yml` — the infra this module owes the project

`claude.md` lists this under Known Gaps and assigns it to Module 4. Nothing in this
design can be run, let alone tested, without it.

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: movieticket
      MYSQL_ALLOW_EMPTY_PASSWORD: "yes"    # local dev only
    ports: ["3306:3306"]
    volumes: ["mysql-data:/var/lib/mysql"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    # No volume, and no AOF/RDB persistence, ON PURPOSE. Lock state is
    # ephemeral by definition - a lock restored from disk has a TTL that is
    # already wrong (see section 9-F). Losing it all on restart is correct.
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 10

volumes:
  mysql-data:
```

The ports and database name match `application.properties`' existing local-dev
defaults, so `docker compose up -d` followed by `./mvnw spring-boot:run` needs no
environment variables at all.

### Properties added

```properties
# Lettuce's default command timeout is 60s - far too long to "fail fast" (section 9-A).
spring.data.redis.timeout=${REDIS_TIMEOUT:1000ms}
spring.data.redis.connect-timeout=${REDIS_CONNECT_TIMEOUT:1000ms}
spring.data.redis.password=${REDIS_PASSWORD:}

# Seat lock TTL. claude.md fixes this at 300s; the property exists so a deployment
# can tune it without a recompile, NOT as an invitation to change it casually.
seatlock.ttl-seconds=${SEAT_LOCK_TTL_SECONDS:300}
```

> **Why the 10-seat cap is *not* a property.** Bean Validation annotations take
> compile-time constants, so `@Size(max = 10)` can't read one. Rather than duplicate
> the limit as both an annotation and a service-layer check that could drift apart,
> the annotation is the single source of truth and 10 is recorded in `claude.md`.

---

## 14. Test plan

| Test | Asserts |
|---|---|
| `lock` happy path (Mockito, mocked `StringRedisTemplate`) | script invoked with the exact keys `seat_lock:12:101/102` and args `[7, 300]`; response carries `secondsRemaining` derived from the returned min TTL |
| `lock` when the script returns `{-1, 102}` | `SeatUnavailableException` thrown, `409`, `details` names seat 102 |
| `lock` a seat belonging to a different show | `SeatNotFoundException` → `404`, **and Redis is never called** (`verifyNoInteractions`) |
| `lock` a seat already `BOOKED` in the DB | `409` before any Redis call |
| `lock` when the template throws `RedisConnectionFailureException` | `SeatLockUnavailableException` → `503`, never a silent success |
| `release` of a lock held by someone else | script returns empty; response reports `releasedCount: 0`; status still `200` |
| `SeatServiceTest` with a stubbed `SeatLockView` | effective status precedence: `BOOKED` beats a lock; locked-and-`AVAILABLE` → `LOCKED`; neither → `AVAILABLE`. **Module 3 shipped with no test for this at all** — the seam finally makes it testable |
| **Concurrency (integration, real Redis):** 20 threads race for the same seat | **exactly one** returns `200`, nineteen get `409`. The headline claim of the whole project, proven rather than asserted |
| Multi-seat all-or-nothing (integration) | user A holds `102`; user B requests `[101,102,103]` → `409`, **and `101`/`103` are still unlocked afterwards** (nothing was written) |
| Idempotent re-lock (integration) | same user locks `101` twice; second call succeeds and `TTL` is **not** refreshed (the no-heartbeat guarantee, §9-C) |
| TTL expiry (integration, short TTL override) | after expiry the seat reports `AVAILABLE` again with no code having run |

Unit tests follow `AuthServiceTest`'s established shape: `@ExtendWith(MockitoExtension
.class)`, hand-constructed service in `@BeforeEach`, `ReflectionTestUtils` for `@Value`
fields, `ArgumentCaptor` to inspect the keys handed to the script. The integration
tests need a real Redis — see §15-C.

---

## 15. Open Decisions

- **A. `DELETE` with a request body, or `POST /seats/lock/release`?** `DELETE` with a
  body is legal, Spring binds it fine, and browser `fetch` sends it — but some proxies
  and older clients strip `DELETE` bodies, and it's the kind of thing that fails only
  in production. Recommendation: **`DELETE` with a body**, since `claude.md` calls for
  both operations under the `/seats/lock` path and it's the more honest verb. If a
  proxy ever eats it, `POST /shows/{showId}/seats/lock/release` is a drop-in fallback.

- **B. Resolve the user id per call, or add a `userId` claim to the JWT?** Today
  `findByUsername` runs on every lock call — one indexed lookup on an endpoint that
  already does several DB reads, so the cost is noise. A `userId` claim would remove it
  but means tokens issued before the change lack the claim, so the code needs a
  fallback anyway during the rollover. Recommendation: **keep the DB lookup.** Revisit
  only if profiling says otherwise.

- **C. How to run the concurrency tests.** (i) Testcontainers (`redis` module) — real
  Redis, hermetic, needs Docker on the build machine and a new test dependency;
  (ii) point the tests at the `docker-compose` Redis and `@Disabled` them by default;
  (iii) skip them. Recommendation: **(i) Testcontainers.** The 20-threads-one-seat test
  is the single most valuable test in this project and it is worthless against a mock.
  Note the existing environment caveat: this sandbox has no JDK 21 and no MySQL, so
  these will need to be run on a real dev machine.

- **D. Per-user lock caps.** Nothing stops one user making ten sequential 10-seat
  requests. Options: a `user_lock_count:{userId}` counter, or nothing.
  Recommendation: **nothing, for now.** The TTL bounds the damage to 300 s, and rate
  limiting is a cross-cutting concern that deserves its own pass rather than being
  smuggled into the lock service. Flagged in the README as a known limitation.

- **E. Publish `SeatLockChangedEvent` now or in Module 6?** Recommendation: **now**
  (§11.2) — zero new dependencies, ~6 lines, and it means Module 6 adds a listener
  rather than editing this module's core logic.

- **F. Convert `Seat.status` to an enum?** Tempting while touching this area, but it
  touches every service comparing against `"AVAILABLE"`/`"BOOKED"` string literals.
  Recommendation: **not in this module.** It's a clean standalone refactor and mixing
  it in would make the concurrency changes harder to review.

---

### Summary of the three things I'd flag loudest

1. **`spring.data.redis.timeout` must be set (§9-A).** Lettuce defaults to a 60-second
   command timeout, so a *hung* Redis — not even a dead one — freezes every request
   thread in the application, including endpoints that never touch Redis. Everything
   else in this design assumes failures surface in about a second. Highest-risk item,
   and the easiest to omit without noticing.

2. **Endpoint #3 needs its SecurityConfig matcher declared *before* the existing
   `permitAll` GET rule (§10).** `/shows/**` is public for GETs, so adding
   `/shows/{id}/seats/locks/mine` without the ordered matcher publishes one user's seat
   selections to anyone who asks. Ship the endpoint and the matcher in the same commit.

3. **The `SeatLockView` signature change is a promise being broken on purpose (§8).**
   `plan/crud.md` §6 and `logic/listing-deep-dive.md` §2.7 both state that `SeatService`
   would need zero changes. It needs one line, so that the hottest endpoint in the
   system does an exact `MGET` instead of an O(keyspace) `SCAN`. The seam's real
   guarantee — no DTO, controller, or status-precedence change — still holds. Both docs
   and `claude.md` have been updated to say so rather than leaving a stale promise.
