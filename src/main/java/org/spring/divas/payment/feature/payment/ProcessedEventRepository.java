package org.spring.divas.payment.feature.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(UUID eventId);
}