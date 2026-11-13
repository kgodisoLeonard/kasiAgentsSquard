package com.spaza.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AppStateRepository extends JpaRepository<AppState, String> {
  List<AppState> findByKind(String kind);
}
