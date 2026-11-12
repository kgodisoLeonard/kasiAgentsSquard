package com.spaza.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Demo identity gateway. Replace seeded accounts with OIDC/MFA in production. */
@RestController @RequestMapping("/api/auth")
public class AuthController {
  @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body) {
    String email=String.valueOf(body.getOrDefault("email","")).trim().toLowerCase(Locale.ROOT);
    String password=String.valueOf(body.getOrDefault("password", ""));
    if ("owner@spaza.local".equals(email) && "spaza123".equals(password)) return ResponseEntity.ok(Map.of("success",true,"user",Map.of("name","Thandi Mokone","role","OWNER","shop","Thandi's Spaza","verified",true)));
    if ("supplier@mokoena.local".equals(email) && "supplier123".equals(password)) return ResponseEntity.ok(Map.of("success",true,"user",Map.of("name","Mokoena Meats","role","SUPPLIER","shop","Mokoena Meats (Pty) Ltd","verified",true)));
    return ResponseEntity.status(401).body(Map.of("error","Invalid email or password"));
  }
}
