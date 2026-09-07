package com.example.movieticket.service;

import com.example.movieticket.dto.SeatDto;
import com.example.movieticket.dto.SeatLayoutResponse;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.Show;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Module 3 shipped {@link SeatService} with no test at all for its effective-status
 * precedence rule (claude.md principle #1) - the seam existing at all is what
 * finally makes it testable, now that {@link SeatLockView} can be stubbed instead
 * of requiring a real Redis. See plan/redis.md section 14.
 */
@ExtendWith(MockitoExtension.class)
class SeatServiceTest {

    @Mock private ShowRepository showRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private SeatLockView seatLockView;

    private SeatService seatService;

    private static final Long SHOW_ID = 12L;

    @BeforeEach
    void setUp() {
        seatService = new SeatService(showRepository, seatRepository, seatLockView);
        when(showRepository.existsById(SHOW_ID)).thenReturn(true);
    }

    @Test
    void getSeatLayout_bookedSeatBeatsALock() {
        // Seat 101 is durably BOOKED in the DB. Even if Redis (incorrectly, or via
        // a stale lock that never got cleaned up) still reports it as locked, the
        // effective status must stay BOOKED - claude.md principle #1's precedence.
        when(seatRepository.findByShowId(SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).seatNumber("A1").status("BOOKED").show(Show.builder().build()).build()
        ));
        when(seatLockView.lockedSeatIds(any(), anyCollection())).thenReturn(Set.of(101L));

        SeatDto dto = onlySeat(seatService.getSeatLayout(SHOW_ID));
        assertEquals("BOOKED", dto.getStatus());
    }

    @Test
    void getSeatLayout_lockedAndAvailableInDb_reportsLocked() {
        when(seatRepository.findByShowId(SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).seatNumber("A1").status("AVAILABLE").show(Show.builder().build()).build()
        ));
        when(seatLockView.lockedSeatIds(any(), anyCollection())).thenReturn(Set.of(101L));

        SeatDto dto = onlySeat(seatService.getSeatLayout(SHOW_ID));
        assertEquals("LOCKED", dto.getStatus());
    }

    @Test
    void getSeatLayout_neitherBookedNorLocked_reportsAvailable() {
        when(seatRepository.findByShowId(SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).seatNumber("A1").status("AVAILABLE").show(Show.builder().build()).build()
        ));
        when(seatLockView.lockedSeatIds(any(), anyCollection())).thenReturn(Set.of());

        SeatDto dto = onlySeat(seatService.getSeatLayout(SHOW_ID));
        assertEquals("AVAILABLE", dto.getStatus());
    }

    @Test
    void getSeatLayout_passesTheSeatIdsItAlreadyLoaded_toTheLockView() {
        // Module 4's amendment to the seam (claude.md, "Amended by Module 4's
        // design"): SeatService must hand SeatLockView the ids it already has,
        // not just the showId, so a Redis implementation can do one exact MGET
        // instead of an O(keyspace) SCAN.
        when(seatRepository.findByShowId(SHOW_ID)).thenReturn(List.of(
                Seat.builder().id(101L).seatNumber("A1").status("AVAILABLE").show(Show.builder().build()).build(),
                Seat.builder().id(102L).seatNumber("A2").status("AVAILABLE").show(Show.builder().build()).build()
        ));
        when(seatLockView.lockedSeatIds(any(), anyCollection())).thenReturn(Set.of());

        seatService.getSeatLayout(SHOW_ID);

        org.mockito.ArgumentCaptor<java.util.Collection<Long>> captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(seatLockView).lockedSeatIds(org.mockito.ArgumentMatchers.eq(SHOW_ID), captor.capture());
        assertEquals(Set.of(101L, 102L), Set.copyOf(captor.getValue()));
    }

    private SeatDto onlySeat(SeatLayoutResponse response) {
        return response.getSeats().get(0);
    }
}
