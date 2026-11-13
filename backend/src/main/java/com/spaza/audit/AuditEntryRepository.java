package com.spaza.audit;

import org.springframework.data.repository.Repository;
import java.util.List;
import java.util.Optional;

/** Deliberately not a CrudRepository: there is no update, delete or deleteAll on the audit log. */
public interface AuditEntryRepository extends Repository<AuditEntry, Long> {
  AuditEntry save(AuditEntry entry);
  Optional<AuditEntry> findTopByOrderBySeqDesc();
  List<AuditEntry> findAllByOrderBySeqAsc();
  List<AuditEntry> findTop200ByOrderBySeqDesc();
}
