package com.example.movieticket.repository;

import com.example.movieticket.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    // Module 5 (plan/payment.md section 4.2): the PESSIMISTIC_WRITE row lock this
    // method takes IS BookingService.confirmPaid's entire concurrency story -
    // idempotency gate (a retried webhook blocks until the first commits, then
    // reads CONFIRMED and returns without doing anything), race gate between the
    // browser-callback and webhook paths, and serialization point for the seat
    // writes, all from one lock on one row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByProviderOrderId(String providerOrderId);
}
