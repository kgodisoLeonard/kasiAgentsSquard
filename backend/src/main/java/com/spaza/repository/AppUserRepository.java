package com.spaza.repository;

import com.spaza.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
  Optional<AppUser> findByEmail(String email);
  boolean existsByEmail(String email);
  List<AppUser> findByBusinessNameIgnoreCase(String businessName);
  boolean existsByBusinessNameIgnoreCase(String businessName);
}
