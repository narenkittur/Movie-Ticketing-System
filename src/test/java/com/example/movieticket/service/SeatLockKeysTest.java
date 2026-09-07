package com.example.movieticket.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the key-format single source of truth (claude.md's "Redis
 * locking standards"). {@code showIdFromKey}/{@code isSeatLockKey} are Module 6
 * additions (plan/websockets.md section 5.1/section 10 test 7) -
 * {@code SeatLockExpiryListener} needs both halves of the key parsed correctly to
 * build a {@link SeatLockChangedEvent}.
 */
class SeatLockKeysTest {

    @Test
    void key_producesTheDocumentedLiteralFormat() {
        assertEquals("seat_lock:12:101", SeatLockKeys.key(12L, 101L));
    }

    @Test
    void seatIdFromKey_extractsTheTrailingSegment() {
        assertEquals(101L, SeatLockKeys.seatIdFromKey("seat_lock:12:101"));
    }

    @Test
    void showIdFromKey_extractsTheMiddleSegment() {
        assertEquals(12L, SeatLockKeys.showIdFromKey("seat_lock:12:101"));
    }

    @Test
    void isSeatLockKey_trueForOwnFormat_falseForAnythingElse() {
        assertTrue(SeatLockKeys.isSeatLockKey("seat_lock:12:101"));
        assertFalse(SeatLockKeys.isSeatLockKey("refresh_token:abc123"));
        assertFalse(SeatLockKeys.isSeatLockKey(null));
    }
}
