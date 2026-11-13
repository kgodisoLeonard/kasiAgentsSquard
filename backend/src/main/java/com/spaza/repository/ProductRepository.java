package com.spaza.repository;

import com.spaza.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Every query is scoped by owner: there is no way to list or load another shop's stock through this repository. */
public interface ProductRepository extends JpaRepository<Product, String> {
  List<Product> findByOwnerIdOrderByNameAsc(String ownerId);
  Optional<Product> findByIdAndOwnerId(String id, String ownerId);
  Optional<Product> findFirstByOwnerIdAndBarcode(String ownerId, String barcode);
  Optional<Product> findByOwnerIdAndNameIgnoreCase(String ownerId, String name);
  boolean existsByOwnerId(String ownerId);
}
