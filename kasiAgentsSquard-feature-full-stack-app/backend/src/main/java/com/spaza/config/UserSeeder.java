package com.spaza.config;

import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class UserSeeder {
  @Bean CommandLineRunner seedUsers(AppUserRepository users) {
    return args -> { BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      if (!users.existsByEmail("owner@spaza.local")) users.save(new AppUser("owner-demo", "Thandi Mokone", "owner@spaza.local", encoder.encode("spaza123"), "OWNER", "Thandi's Spaza", true));
      if (!users.existsByEmail("supplier@mokoena.local")) users.save(new AppUser("supplier-demo", "Mokoena Meats", "supplier@mokoena.local", encoder.encode("supplier123"), "SUPPLIER", "Mokoena Meats (Pty) Ltd", true));
    };
  }
}
