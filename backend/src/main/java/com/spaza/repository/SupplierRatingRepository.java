package com.spaza.repository;

import com.spaza.model.SupplierRating;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SupplierRatingRepository extends JpaRepository<SupplierRating, String> {
  List<SupplierRating> findBySupplierIdOrderByCreatedAtDesc(String supplierId);
  Optional<SupplierRating> findBySupplierIdAndOrderId(String supplierId, String orderId);
}
