package com.example.movieticket.service;

import com.example.movieticket.dto.SeatDto;
import com.example.movieticket.dto.SeatLayoutRequest;
import com.example.movieticket.dto.SeatLayoutResponse;
import com.example.movieticket.exception.SeatsAlreadyGeneratedException;
import com.example.movieticket.exception.ShowNotFoundException;
import com.example.movieticket.model.Seat;
import com.example.movieticket.model.Show;
import com.example.movieticket.repository.SeatRepository;
import com.example.movieticket.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Business logic behind the Seat half of Module 3 (plan/crud.md endpoints #4, #7):
 * bulk-generating a show's seat inventory, and reporting the live seat map. This is
 * the module's most architecturally sensitive piece - see plan/crud.md section 6
 * for why "LOCKED" is computed here rather than ever being read from the DB.
 */
@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    // Every freshly generated seat starts life free. Plain-String status (matching
    // how Seat.status itself is modeled today - see claude.md's note on why these
    // aren't enums yet) rather than a magic literal repeated at each call site.
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_BOOKED = "BOOKED";
    private static final String STATUS_LOCKED = "LOCKED";

    // Parses a generated seat number like "A1" or "AA12" back into its row label
    // ("A"/"AA") and column number (1/12) - used only to reconstruct the grid
    // shape (rows x seatsPerRow) for GET /shows/{id}/seats, since that shape isn't
    // separately persisted anywhere (see getSeatLayout()'s javadoc below).
    private static final Pattern SEAT_NUMBER_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)$");

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    // The Module 3/Module 4 seam (see SeatLockView's javadoc) - today this is
    // always NoOpSeatLockView, injected by type since it's the only SeatLockView
    // bean on the context.
    private final SeatLockView seatLockView;

    public SeatService(ShowRepository showRepository, SeatRepository seatRepository, SeatLockView seatLockView) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.seatLockView = seatLockView;
    }

    /**
     * POST /admin/shows/{showId}/seats. Expands a compact {rows, seatsPerRow}
     * spec into individual Seat rows in one batch insert. Idempotency is enforced
     * up front (existsByShowId) so retrying this call never silently doubles a
     * show's inventory - see Seat's DB-level UNIQUE(show_id, seat_number)
     * constraint for the belt-and-braces guard under concurrent calls.
     */
    @Transactional
    public SeatLayoutResponse generateLayout(Long showId, SeatLayoutRequest request) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("No show found with id " + showId));

        if (seatRepository.existsByShowId(showId)) {
            log.warn("Rejected duplicate seat-layout generation for show id={}", showId);
            throw new SeatsAlreadyGeneratedException("Seats have already been generated for show " + showId);
        }

        int rows = request.getRows();
        int seatsPerRow = request.getSeatsPerRow();
        List<String> rowLabels = resolveRowLabels(request.getRowLabels(), rows);

        // Build the full grid in memory first, then save it in one batch call
        // below - one flush/round-trip instead of `rows * seatsPerRow` individual
        // inserts.
        List<Seat> seats = new ArrayList<>(rows * seatsPerRow);
        for (int r = 0; r < rows; r++) {
            String rowLabel = rowLabels.get(r);
            for (int c = 1; c <= seatsPerRow; c++) {
                seats.add(Seat.builder()
                        .seatNumber(rowLabel + c) // e.g. "A1", "A2", ... "H12"
                        .status(STATUS_AVAILABLE)
                        .show(show)
                        .build());
            }
        }

        List<Seat> saved = seatRepository.saveAll(seats);
        log.info("Generated {} seats ({} rows x {} per row) for show id={}", saved.size(), rows, seatsPerRow, showId);

        List<SeatDto> seatDtos = saved.stream()
                .map(seat -> toSeatDto(seat, Set.of())) // freshly generated: nothing can be locked/booked yet
                .toList();

        return SeatLayoutResponse.builder()
                .showId(showId)
                .rows(rows)
                .seatsPerRow(seatsPerRow)
                .seats(seatDtos)
                .build();
    }

    /**
     * GET /shows/{showId}/seats - the public, live seat map. This is where
     * claude.md's principle #1 ("never infer real-time lock state from the DB")
     * actually gets enforced: every seat's status here is computed by overlaying
     * SeatLockView's answer on top of the durable DB status, never read as a raw
     * copy of Seat.status (see plan/crud.md section 6's table of which status
     * lives where).
     */
    @Transactional(readOnly = true)
    public SeatLayoutResponse getSeatLayout(Long showId) {
        if (!showRepository.existsById(showId)) {
            throw new ShowNotFoundException("No show found with id " + showId);
        }

        List<Seat> seats = seatRepository.findByShowId(showId);
        // One Redis lookup for the whole show rather than one per seat - see
        // SeatLockView.lockedSeatIds()'s javadoc. Module 4 amended this call to
        // pass the seat ids already loaded above, so RedisSeatLockView can do one
        // exact MGET instead of an O(keyspace) SCAN (claude.md, Architectural
        // Principles, "Amended by Module 4's design").
        Set<Long> lockedSeatIds = seatLockView.lockedSeatIds(
                showId, seats.stream().map(Seat::getId).toList());

        List<SeatDto> seatDtos = seats.stream()
                .map(seat -> toSeatDto(seat, lockedSeatIds))
                .toList();

        // The original {rows, seatsPerRow} spec isn't persisted anywhere as its
        // own record (no ShowLayout entity - see plan/crud.md section 11-B, "no
        // schema-change" path) - only the flat list of generated Seat rows is. So
        // the grid shape reported here is reconstructed from the seat numbers
        // themselves rather than looked up.
        int[] shape = deriveShape(seats);

        return SeatLayoutResponse.builder()
                .showId(showId)
                .rows(shape[0])
                .seatsPerRow(shape[1])
                .seats(seatDtos)
                .build();
    }

    /**
     * Effective status = BOOKED always wins (durable, sold) &gt; LOCKED if
     * Redis says this seat id is currently held &gt; otherwise whatever the DB
     * says (AVAILABLE in practice, since BOOKED is already handled above and
     * LOCKED is never persisted - see class javadoc).
     */
    private SeatDto toSeatDto(Seat seat, Set<Long> lockedSeatIds) {
        String effectiveStatus;
        if (STATUS_BOOKED.equals(seat.getStatus())) {
            effectiveStatus = STATUS_BOOKED;
        } else if (lockedSeatIds.contains(seat.getId())) {
            effectiveStatus = STATUS_LOCKED;
        } else {
            effectiveStatus = seat.getStatus();
        }

        return SeatDto.builder()
                .id(seat.getId())
                .seatNumber(seat.getSeatNumber())
                .status(effectiveStatus)
                .build();
    }

    /** Default row labels A, B, C... when the request doesn't supply explicit ones. */
    private List<String> resolveRowLabels(List<String> requested, int rows) {
        if (requested == null || requested.isEmpty()) {
            List<String> generated = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                // 'A' + r: SeatLayoutRequest caps `rows` at 26 specifically so
                // this never has to roll over past 'Z' into double letters.
                generated.add(String.valueOf((char) ('A' + r)));
            }
            return generated;
        }

        if (requested.size() != rows) {
            throw new IllegalArgumentException(
                    "rowLabels must contain exactly " + rows + " entries (got " + requested.size() + ")");
        }
        return requested;
    }

    /** @return {rows, seatsPerRow} derived from the persisted seat numbers, or {0, 0} if none exist yet. */
    private int[] deriveShape(List<Seat> seats) {
        if (seats.isEmpty()) {
            return new int[]{0, 0};
        }

        Set<String> distinctRowLabels = new HashSet<>();
        int maxColumn = 0;
        for (Seat seat : seats) {
            Matcher matcher = SEAT_NUMBER_PATTERN.matcher(seat.getSeatNumber());
            if (matcher.matches()) {
                distinctRowLabels.add(matcher.group(1));
                maxColumn = Math.max(maxColumn, Integer.parseInt(matcher.group(2)));
            }
        }
        return new int[]{distinctRowLabels.size(), maxColumn};
    }
}
