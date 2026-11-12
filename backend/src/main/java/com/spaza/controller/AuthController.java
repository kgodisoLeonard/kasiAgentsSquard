package com.spaza.controller;

import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
  private final AppUserRepository users;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  public AuthController(AppUserRepository users) { this.users = users; }

  @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body) {
    String email=text(body,"email").toLowerCase(Locale.ROOT), password=text(body,"password");
    Optional<AppUser> match=users.findByEmail(email).filter(user -> encoder.matches(password,user.getPasswordHash()));
    return match.map(user -> ResponseEntity.ok(Map.of("success",true,"user",userView(user))))
      .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error","Invalid email or password")));
  }

  @PostMapping("/register") public ResponseEntity<?> register(@RequestBody Map<String,Object> body) {
    String name=text(body,"name"), email=text(body,"email").toLowerCase(Locale.ROOT), password=text(body,"password"), business=text(body,"businessName"), role=text(body,"role").toUpperCase(Locale.ROOT);
    if(name.isBlank()||email.isBlank()||password.length()<6||business.isBlank()||!(role.equals("OWNER")||role.equals("SUPPLIER"))) return ResponseEntity.badRequest().body(Map.of("error","Name, business name, valid role and a password of at least 6 characters are required"));
    if(users.existsByEmail(email)) return ResponseEntity.status(409).body(Map.of("error","An account with this email already exists"));
    AppUser user=users.save(new AppUser(UUID.randomUUID().toString(),name,email,encoder.encode(password),role,business,false));
    return ResponseEntity.status(201).body(Map.of("success",true,"user",userView(user)));
  }
  private String text(Map<String,Object> body,String key){return String.valueOf(body.getOrDefault(key,"" )).trim();}
  private Map<String,Object> userView(AppUser user){return Map.of("id",user.getId(),"name",user.getName(),"email",user.getEmail(),"role",user.getRole(),"shop",user.getBusinessName(),"verified",user.isVerified());}
}
