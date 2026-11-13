package com.spaza.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionRecord, String> {
  Optional<SessionRecord> findByAccessHash(String accessHash);
  Optional<SessionRecord> findByRefreshHash(String refreshHash);
  List<SessionRecord> findByUserId(String userId);
}
