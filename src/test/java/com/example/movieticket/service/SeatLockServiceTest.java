package com.example.movieticket.service;

import com.example.movieticket.dto.SeatLockReleaseResponse;
import com.example.movieticket.dto.SeatLockRequest;
import com.example.movieticket.dto.SeatLockResponse;
import com.example.movieticket.exception.SeatLockUnavailableException;
import com.example.movieticket.exception.SeatNotFoundException;
import com.example.movieticket.exception.SeatUnavailableException;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.User;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import com.example.movieticket.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SeatLockService's business rules, with all collaborators mocked -
 * same shape as AuthServiceTest (see that class's javadoc for the rationale).
 * Follows the scenario table in plan/redis.md section 14. The concurrency and TTL
 * scenarios in that table need a real Redis and are deliberately NOT here - see
 * claude.md's Known Gaps for why (no Testcontainers dependency yet, and this
 * sandbox has no Docker/JDK 21 to run one anyway).
 */
@ExtendWith(MockitoExtension.class)
class SeatLockServiceTest {

    @Mock private ShowRepository showRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultRedisScript<List> lockSeatsScript;
    private DefaultRedisScript<List> unlockSeatsScript;
    private SeatLockService seatLockService;

    private static final Long SHOW_ID = 12L;
    private static final User ALICE = User.builder().id(7L).username("alice").role("ROLE_USER").build();

    @BeforeEach
    void setUp() {
        lockSeatsScript = new DefaultRedisScript<>();
        unlockSeatsScript = new DefaultRedisScript<>();
        seatLockService = new SeatLockService(showRepository, seatRepository, userRepository,
                redisTemplate, lockSeatsScript, unlockSeatsScript, eventPublisher);
        ReflectionTestUtils.setField(seatLockService, "ttlSeconds", 300L);

        lenient().when(userRepository.findByUsername("alice")).thenReturn(Optional.of(ALICE));
    }

    @Test
    void lock_happyPath_invokesScriptWithExactKeysAndArgs_andReturnsSecondsRemaining() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        when(seatRepository.findByIdInAndShowId(List.of(101L, 102L), SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).status("AVAILABLE").build(),
                Seat.builder().id(102L).status("AVAILABLE").build()
        ));
        when(redisTemplate.execute(eq(lockSeatsScript), anyList(), any(), any()))
                .thenReturn(List.of(299999L));

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L, 102L)).build();
        SeatLockResponse response = seatLockService.lock(SHOW_ID, request, "alice");

        assertEquals(299L, response.getSecondsRemaining());
        assertEquals(List.of(101L, 102L), response.getSeatIds());

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(eq(lockSeatsScript), keysCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());
        assertEquals(List.of("seat_lock:12:101", "seat_lock:12:102"), keysCaptor.getValue());
        assertEquals(List.of("7", "300"), argsCaptor.getAllValues());

        verify(eventPublisher).publishEvent(any(SeatLockChangedEvent.class));
    }

    @Test
    void lock_whenScriptReturnsConflict_throwsSeatUnavailableExceptionNamingTheCulprit() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        when(seatRepository.findByIdInAndShowId(List.of(101L, 102L), SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).status("AVAILABLE").build(),
                Seat.builder().id(102L).status("AVAILABLE").build()
        ));
        when(redisTemplate.execute(eq(lockSeatsScript), anyList(), any(), any()))
                .thenReturn(List.of(-1L, 102L));

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L, 102L)).build();

        SeatUnavailableException ex = assertThrows(SeatUnavailableException.class,
                () -> seatLockService.lock(SHOW_ID, request, "alice"));
        assertTrue(ex.getDetails().get(0).contains("102"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void lock_seatBelongingToDifferentShow_throwsNotFound_andNeverTouchesRedis() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        // Only one of the two requested seats actually belongs to show 12.
        when(seatRepository.findByIdInAndShowId(List.of(101L, 999L), SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).status("AVAILABLE").build()
        ));

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L, 999L)).build();

        assertThrows(SeatNotFoundException.class, () -> seatLockService.lock(SHOW_ID, request, "alice"));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void lock_seatAlreadyBooked_throwsConflict_beforeAnyRedisCall() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        when(seatRepository.findByIdInAndShowId(List.of(101L), SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).status("BOOKED").build()
        ));

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L)).build();

        assertThrows(SeatUnavailableException.class, () -> seatLockService.lock(SHOW_ID, request, "alice"));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void lock_whenRedisIsUnreachable_throwsSeatLockUnavailable_neverAssumesFree() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        when(seatRepository.findByIdInAndShowId(List.of(101L), SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).status("AVAILABLE").build()
        ));
        when(redisTemplate.execute(eq(lockSeatsScript), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L)).build();

        assertThrows(SeatLockUnavailableException.class, () -> seatLockService.lock(SHOW_ID, request, "alice"));
    }

    @Test
    void release_ofALockHeldBySomeoneElse_returnsZeroReleasedWithoutError() {
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
        // unlock_seats.lua's compare-and-delete silently skips keys the caller
        // doesn't own - an empty list back, not an error.
        when(redisTemplate.execute(eq(unlockSeatsScript), anyList(), any()))
                .thenReturn(List.of());

        SeatLockRequest request = SeatLockRequest.builder().seatIds(List.of(101L, 102L)).build();
        SeatLockReleaseResponse response = seatLockService.release(SHOW_ID, request, "alice");

        assertEquals(0, response.getReleasedCount());
        assertTrue(response.getReleasedSeatIds().isEmpty());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void assertHoldsAll_whenCallerHoldsEverySeat_doesNotThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(List.of("7", "7"));

        assertDoesNotThrow(() -> seatLockService.assertHoldsAll(SHOW_ID, List.of(101L, 102L), 7L));
    }

    @Test
    void assertHoldsAll_whenOneSeatIsNoLongerHeldByCaller_throwsSeatLockExpired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // seat 102 expired (or was never held) - the value is null/mismatched.
        when(valueOperations.multiGet(anyList())).thenReturn(java.util.Arrays.asList("7", null));

        assertThrows(com.example.movieticket.exception.SeatLockExpiredException.class,
                () -> seatLockService.assertHoldsAll(SHOW_ID, List.of(101L, 102L), 7L));
    }

    // --- releaseAfterCommit (Module 6, plan/websockets.md section 4.2/4.3) ---
    //
    // registerSynchronization requires an active transaction synchronization, which
    // these mock-only unit tests don't have - initSynchronization()/
    // triggerAfterCommit()/clearSynchronization() simulate the commit exactly like
    // Spring's real TransactionSynchronizationManager would at the real commit
    // point, without needing an actual DB transaction.

    @Test
    void releaseAfterCommit_booked_publishesBooked_evenWhenUnlockScriptThrows() {
        when(redisTemplate.execute(eq(unlockSeatsScript), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            seatLockService.releaseAfterCommit(SHOW_ID, List.of(101L), 7L, SeatLockChangedEvent.Type.BOOKED);
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The booking already committed in MySQL regardless of whether Redis heard
        // about the unlock - the seat IS sold, so BOOKED must go out unconditionally
        // (plan/websockets.md section 4.3's table, row 2).
        verify(eventPublisher).publishEvent(argThat((SeatLockChangedEvent e) ->
                e.type() == SeatLockChangedEvent.Type.BOOKED && e.seatIds().equals(List.of(101L))));
    }

    @Test
    void releaseAfterCommit_released_publishesNothing_whenUnlockScriptThrows() {
        when(redisTemplate.execute(eq(unlockSeatsScript), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            seatLockService.releaseAfterCommit(SHOW_ID, List.of(101L), 7L, SeatLockChangedEvent.Type.RELEASED);
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // Broadcasting AVAILABLE when the lock might still stand in Redis would be a
        // lie (plan/websockets.md section 4.3's table, row 4) - SeatLockExpiryListener
        // announces the truth once the key actually dies.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void releaseAfterCommit_released_publishesReleased_whenUnlockScriptSucceeds() {
        when(redisTemplate.execute(eq(unlockSeatsScript), anyList(), any())).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            seatLockService.releaseAfterCommit(SHOW_ID, List.of(101L, 102L), 7L, SeatLockChangedEvent.Type.RELEASED);
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(eventPublisher).publishEvent(argThat((SeatLockChangedEvent e) ->
                e.type() == SeatLockChangedEvent.Type.RELEASED && e.seatIds().equals(List.of(101L, 102L))));
    }
}
