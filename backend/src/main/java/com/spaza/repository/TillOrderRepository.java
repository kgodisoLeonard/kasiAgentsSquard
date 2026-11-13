package com.spaza.repository;

import com.spaza.model.TillOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TillOrderRepository extends JpaRepository<TillOrder, String> {
  Optional<TillOrder> findFirstByOwnerIdAndStatusOrderByOpenedAtDesc(String ownerId, String status);
  Optional<TillOrder> findByIdAndOwnerId(String id, String ownerId);
  List<TillOrder> findByOwnerId(String ownerId);
}
