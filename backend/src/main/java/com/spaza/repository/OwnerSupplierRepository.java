package com.spaza.repository;

import com.spaza.model.OwnerSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OwnerSupplierRepository extends JpaRepository<OwnerSupplier, String> {
  List<OwnerSupplier> findByOwnerIdOrderByAddedAtAsc(String ownerId);
  Optional<OwnerSupplier> findByOwnerIdAndSupplierId(String ownerId, String supplierId);
}
