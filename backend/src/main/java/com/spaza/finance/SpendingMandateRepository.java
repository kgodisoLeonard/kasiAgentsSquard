package com.spaza.finance;

import org.springframework.data.repository.Repository;
import java.util.Optional;

/** No delete operation: mandates can be tightened or loosened by their owner, never silently removed. */
public interface SpendingMandateRepository extends Repository<SpendingMandate, String> {
  SpendingMandate save(SpendingMandate mandate);
  Optional<SpendingMandate> findById(String ownerId);
}
