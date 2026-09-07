package com.example.movieticket.repository;

import com.example.movieticket.model.Booking;
import com.example.movieticket.model.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Find all bookings for a specific user (for "My Orders" page)
    List<Booking> findByUserId(Long userId);

    // Find all bookings for a specific show (useful for admins to see theater occupancy)
    List<Booking> findByShowId(Long showId);

    // Module 5 (plan/payment.md section 8.5): ownership is a QUERY PREDICATE, not
    // a load-then-compare a later refactor could drop. GET /bookings/{id} and the
    // confirm/cancel paths all resolve through this, returning 404 (not 403) for
    // someone else's booking so booking ids aren't enumerable.
    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    Optional<Booking> findByBookingReference(String bookingReference);

    // GET /bookings' N+1 guard (plan/payment.md section 7.5): Booking's @ManyToOne
    // associations default to EAGER and bookingSeats/payment are otherwise lazy,
    // so a naive list of 20 bookings would be 40+ queries. `distinct` compensates
    // for the fetch join's row multiplication across bookingSeats.
    @Query("select distinct b from Booking b "
         + "join fetch b.bookingSeats bs join fetch bs.seat "
         + "left join fetch b.payment "
         + "where b.user.id = :userId order by b.bookingTime desc")
    List<Booking> findAllForUserWithSeats(@Param("userId") Long userId);

    // Open Decision B (plan/payment.md section 13-B): candidates for
    // BookingService's create-time dedupe check - fetch-joined so the seat-id
    // comparison against the newly requested set doesn't need a second query per
    // candidate. Narrowed to PENDING only; BookingService still re-checks
    // `expiresAt` itself since "PENDING but expired" is exactly the state this
    // dedupe exists to NOT hand back.
    @Query("select distinct b from Booking b "
         + "join fetch b.bookingSeats bs join fetch bs.seat "
         + "where b.user.id = :userId and b.show.id = :showId and b.status = :status")
    List<Booking> findByUserIdAndShowIdAndStatusWithSeats(@Param("userId") Long userId,
                                                           @Param("showId") Long showId,
                                                           @Param("status") BookingStatus status);

    // Module 7 (plan/qrtickets.md section 5.2): a direct mirror of
    // PaymentRepository.findWithLockByProviderOrderId - the PESSIMISTIC_WRITE row
    // lock here IS TicketService.validate()'s entire concurrency story for the
    // double-scan race, one step downstream of confirmPaid's identical pattern.
    // join fetch alongside PESSIMISTIC_WRITE also takes row locks on the joined
    // shows/movies rows on MySQL - accepted as harmless (read-mostly reference
    // rows, nothing else locks them in a conflicting order) rather than a second,
    // unlocked query for the display fields.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b join fetch b.show s join fetch s.movie "
         + "where b.bookingReference = :ref")
    Optional<Booking> findWithLockByBookingReference(@Param("ref") String ref);

    // Module 7 (plan/qrtickets.md section 4.4): GET /bookings/{id}/ticket's N+1
    // guard - extends findByIdAndUserId's ownership-as-query-predicate pattern
    // (section 8.5 above) with a fetch join across show -> movie and
    // bookingSeats -> seat, modelled on findAllForUserWithSeats' distinct + join
    // fetch pattern.
    @Query("select distinct b from Booking b "
         + "join fetch b.show s join fetch s.movie "
         + "join fetch b.bookingSeats bs join fetch bs.seat "
         + "where b.id = :id and b.user.id = :userId")
    Optional<Booking> findByIdAndUserIdWithDetail(@Param("id") Long id, @Param("userId") Long userId);
}
