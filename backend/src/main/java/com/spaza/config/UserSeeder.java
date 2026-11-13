package com.spaza.config;

import com.spaza.finance.MandateService;
import com.spaza.inventory.InventoryService;
import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.List;

/** DEMO accounts with published passwords. Never enable this seeder in production. */
@Configuration
public class UserSeeder {
  private record Demo(String id, String name, String email, String password, String role, String business) {}
  private static final List<Demo> DEMO = List.of(
    new Demo("owner-demo", "Thandi Mokone", "owner@spaza.local", "spaza123", "OWNER", "Thandi's Spaza"),
    new Demo("owner-demo-2", "Sipho Ndlovu", "sipho@spaza.local", "spaza123", "OWNER", "Sipho's Corner Shop"),
    new Demo("supplier-demo", "Mokoena Meats", "supplier@mokoena.local", "supplier123", "SUPPLIER", "Mokoena Meats (Pty) Ltd"),
    new Demo("supplier-ubuntu", "Ubuntu Foods Co-op", "ubuntu@coop.local", "supplier123", "SUPPLIER", "Ubuntu Foods Co-op"),
    new Demo("admin-1", "Reviewer Nomsa", "reviewer1@spaza.local", "review123", "ADMIN", "Spaza Trust & Safety 1"),
    new Demo("admin-2", "Reviewer Pieter", "reviewer2@spaza.local", "review123", "ADMIN", "Spaza Trust & Safety 2"));

  @Bean CommandLineRunner seedUsers(AppUserRepository users, MandateService mandates, InventoryService inventory) {
    return args -> {
      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      for (Demo d : DEMO) if (!users.existsByEmail(d.email())) users.save(new AppUser(d.id(), d.name(), d.email(), encoder.encode(d.password()), d.role(), d.business(), true));
      for (String owner : List.of("owner-demo", "owner-demo-2")) mandates.ensureDefault(owner, List.of("SUP-001", "SUP-002"));
      // DEMO restock rules for Thandi: the agent may reorder these items on its own, within an R3,000 monthly budget.
      mandates.ensureDefaultRestock("owner-demo", new BigDecimal("3000.00"), List.of(
          new MandateService.RestockRule("White Bread (700g)", 5, 24, "SUP-002", new BigDecimal("15.00")),
          new MandateService.RestockRule("Maize Meal (10kg)", 5, 10, "SUP-002", new BigDecimal("90.00")),
          new MandateService.RestockRule("Full Cream Milk (2L)", 5, 12, "SUP-002", new BigDecimal("28.00"))));
      // DEMO restock rules for Sipho's shop, so the agent restocks each shop separately under its own owner's rules.
      mandates.ensureDefaultRestock("owner-demo-2", new BigDecimal("2000"), List.of(
          new MandateService.RestockRule("White Bread (700g)", 6, 20, "SUP-002", new BigDecimal("14.00")),
          new MandateService.RestockRule("Full Cream Milk (2L)", 4, 10, "SUP-002", new BigDecimal("26.00"))));
      // DEMO stock: every spaza owner (demo or registered) gets their own random shelf if they have none yet.
      for (AppUser u : users.findAll()) if ("OWNER".equals(u.getRole())) inventory.seedRandomStock(u.getId());
    };
  }
}
