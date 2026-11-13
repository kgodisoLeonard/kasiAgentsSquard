package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"))
public class AppUser {
  @Id private String id;
  @Column(nullable = false) private String name;
  @Column(nullable = false, unique = true) private String email;
  @Column(nullable = false) private String passwordHash;
  @Column(nullable = false) private String role;
  @Column(nullable = false) private String businessName;
  @Column(nullable = false) private boolean verified;
  @Column(nullable = false) private Instant createdAt;
  protected AppUser() {}
  public AppUser(String id, String name, String email, String passwordHash, String role, String businessName, boolean verified) { this.id=id; this.name=name; this.email=email; this.passwordHash=passwordHash; this.role=role; this.businessName=businessName; this.verified=verified; this.createdAt=Instant.now(); }
  public String getId(){return id;} public String getName(){return name;} public String getEmail(){return email;} public String getPasswordHash(){return passwordHash;} public String getRole(){return role;} public String getBusinessName(){return businessName;} public boolean isVerified(){return verified;}
}
