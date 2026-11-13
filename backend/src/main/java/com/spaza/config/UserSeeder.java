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
    new Demo("admin-2", "Reviewer Pieter", "reviewer2@spaza.local", "review123", "ADMIN", "Spaza Trust & Safety 2"),
    // Four more connected spaza shops (the demo buying club).
    new Demo("owner-lerato", "Lerato Dlamini", "lerato@spaza.local", "spaza123", "OWNER", "Lerato's Tuck Shop"),
    new Demo("owner-bongani", "Bongani Khumalo", "bongani@spaza.local", "spaza123", "OWNER", "Bongani's Spaza"),
    new Demo("owner-zodwa", "Zodwa Mthembu", "zodwa@spaza.local", "spaza123", "OWNER", "Mama Zodwa's Shop"),
    new Demo("owner-kagiso", "Kagiso Molefe", "kagiso@spaza.local", "spaza123", "OWNER", "Kagiso General Dealer"));

  /** The four buying-club shops and their shares of the demo group order. */
  static final List<String> CLUB = List.of("owner-lerato", "owner-bongani", "owner-zodwa", "owner-kagiso");
  static final String DEMO_ORDER_REASON = "Demo buying club: maize meal for four shops";

  @Bean CommandLineRunner seedUsers(AppUserRepository users, MandateService mandates, InventoryService inventory,
                                    com.spaza.finance.GroupOrderService orders, com.spaza.finance.SupplierCatalog catalog) {
    return args -> {
      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      for (Demo d : DEMO) if (!users.existsByEmail(d.email())) users.save(new AppUser(d.id(), d.name(), d.email(), encoder.encode(d.password()), d.role(), d.business(), true));
      // DEMO suppliers for every category; one is deliberately unverified. Their account id is their supplier id.
      for (com.spaza.inventory.ProductCatalog.DemoSupplier s : com.spaza.inventory.ProductCatalog.DEMO_SUPPLIERS)
        if (!users.existsByEmail(s.email())) users.save(new AppUser(s.id(), s.name(), s.email(), encoder.encode("supplier123"), "SUPPLIER", s.name(), s.verified()));
      List<String> verified = new java.util.ArrayList<>(List.of("SUP-001", "SUP-002"));
      com.spaza.inventory.ProductCatalog.DEMO_SUPPLIERS.stream().filter(com.spaza.inventory.ProductCatalog.DemoSupplier::verified).forEach(s -> verified.add(s.id()));
      for (String owner : List.of("owner-demo", "owner-demo-2")) { mandates.ensureDefault(owner, List.of("SUP-001", "SUP-002")); mandates.ensureAllowed(owner, verified); }
      for (String owner : CLUB) mandates.ensureDefault(owner, verified);
      // Each club shop restocks bread from the bakery and milk from the dairy, under its own rules and budget.
      for (String owner : CLUB) mandates.ensureDefaultRestock(owner, new BigDecimal("2500"), List.of(
          new MandateService.RestockRule("White Bread (700g)", 5, 20, "SUP-003", new BigDecimal("15.00")),
          new MandateService.RestockRule("Full Cream Milk (2L)", 5, 12, "SUP-004", new BigDecimal("28.00"))));
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
      // DEMO group order shared by the four club shops: each approves (pays) its own share; everyone sees every step.
      boolean exists = orders.all().stream().anyMatch(o -> o.shares().stream().anyMatch(s -> CLUB.contains(s.ownerId()))
          && orders.view(o, null).get("reason").equals(DEMO_ORDER_REASON));
      if (!exists) {
        BigDecimal price = catalog.price("SUP-002", "Maize Meal (10kg)").orElseThrow().unitPrice();
        int[] qty = {4, 3, 5, 2};
        List<com.spaza.finance.GroupOrderService.MemberLine> members = new java.util.ArrayList<>();
        for (int i = 0; i < CLUB.size(); i++) {
          AppUser u = users.findById(CLUB.get(i)).orElseThrow();
          members.add(new com.spaza.finance.GroupOrderService.MemberLine(u.getId(), u.getName(), qty[i]));
        }
        orders.draft(new com.spaza.finance.GroupOrderService.DraftRequest("SUP-002", "Maize Meal (10kg)", price, 14, new BigDecimal("120"),
            java.time.Duration.ofDays(7), members, DEMO_ORDER_REASON), com.spaza.audit.AuditLog.ActorType.SYSTEM, "seed");
      }
    };
  }
}
