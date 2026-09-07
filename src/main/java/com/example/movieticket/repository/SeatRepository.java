package com.example.movieticket.repository;

import com.example.movieticket.model.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    // Retrieve all seats for a specific show (e.g., to render the seating map)
    List<Seat> findByShowId(Long showId);

    // Filter seats by status (e.g., to count how many are still available)
    List<Seat> findByShowIdAndStatus(Long showId, String status);

    // Module 3: cheap idempotency guard for seat-layout generation
    // (plan/crud.md section 4.3) - a COUNT/EXISTS query instead of loading the
    // whole seat list just to check "has this been done already?".
    boolean existsByShowId(Long showId);

    // Module 4 (plan/redis.md section 6.1 step 3): does every requested seat id
    // actually belong to this show? SeatLockService compares the size of this
    // result against the distinct requested id count - a mismatch means at least
    // one id is bogus or belongs to a different show. Skipping this check would
    // let a caller "lock" a Redis key nobody ever reads.
    List<Seat> findByIdInAndShowId(Collection<Long> ids, Long showId);

    // Module 5 (plan/payment.md section 7.5): the FOR UPDATE re-read that backs
    // both POST /bookings step A and BookingService.confirmPaid step 4 - the DB
    // is the backstop behind the Redis lock (plan/redis.md section 11.1).
    // "order by s.id" is NOT cosmetic: two concurrent bookings whose seat sets
    // overlap will deadlock in InnoDB if they acquire row locks in different
    // orders. Callers must ALSO sort `ids` before calling - the JPQL `in` clause
    // does not otherwise guarantee acquisition order, so this ORDER BY alone
    // isn't sufficient by itself; the two are belt-and-braces together.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :ids and s.show.id = :showId order by s.id")
    List<Seat> findByIdInAndShowIdForUpdate(@Param("ids") Collection<Long> ids, @Param("showId") Long showId);
}