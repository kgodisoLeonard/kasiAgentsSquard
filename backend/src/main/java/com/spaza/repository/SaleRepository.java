package com.spaza.repository;

import com.spaza.model.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SaleRepository extends JpaRepository<Sale, String> {
  List<Sale> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
