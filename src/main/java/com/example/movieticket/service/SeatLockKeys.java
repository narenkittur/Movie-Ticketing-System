package com.example.movieticket.service;

/**
 * Single source of truth for the Redis seat-lock key format (claude.md's "Redis
 * locking standards"; plan/redis.md section 3): {@code seat_lock:<showId>:<seatId>},
 * e.g. {@code seat_lock:12:101}. Shared by {@link RedisSeatLockView},
 * {@link SeatLockService}, and their tests so the format is written down exactly
 * once rather than duplicated (and risking drift) at every call site.
 *
 * <p>The seat id, not the seat number: {@code A1} isn't unique across the system -
 * every show has an {@code A1} - while {@code Seat.id} is the primary key this
 * seam already traffics in.
 *
 * <p>Note for any future move to Redis Cluster: {@code seat_lock:<showId>:<seatId>}
 * has no hash-tag braces, so a show's seats are not guaranteed to land in the same
 * slot. The multi-key Lua scripts in {@code lock_seats.lua}/{@code unlock_seats.lua}
 * become illegal the moment two of a show's seats land in different slots - the
 * fix, if that day comes, is {@code seat_lock:{<showId>}:<seatId>} so every seat of
 * a show shares a slot by construction. This project runs a single Redis node
 * today, so no braces are used.
 */
public final class SeatLockKeys {

    private static final String PREFIX = "seat_lock:";

    private SeatLockKeys() {
    }

    public static String key(Long showId, Long seatId) {
        return PREFIX + showId + ":" + seatId;
    }

    /** Extracts the seat id from a key produced by {@link #key}, e.g. {@code "seat_lock:12:101"} -&gt; {@code 101L}. */
    public static Long seatIdFromKey(String key) {
        return Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
    }

    /**
     * Extracts the show id from a key produced by {@link #key}, e.g.
     * {@code "seat_lock:12:101"} -&gt; {@code 12L}. Added for Module 6's
     * {@code SeatLockExpiryListener} (plan/websockets.md section 5.1), which needs
     * both halves of the key to build a {@code SeatLockChangedEvent} - reuses this
     * class rather than parsing the key inline in the listener.
     */
    public static Long showIdFromKey(String key) {
        int firstColon = key.indexOf(':');
        int secondColon = key.indexOf(':', firstColon + 1);
        return Long.valueOf(key.substring(firstColon + 1, secondColon));
    }

    /** True if {@code key} was produced by {@link #key} - i.e. carries the {@value #PREFIX} prefix. */
    public static boolean isSeatLockKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }
}
