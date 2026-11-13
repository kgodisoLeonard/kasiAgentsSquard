package com.spaza.controller;

import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@RestController @RequestMapping("/api/auth")
public class AuthController {
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final int MAX_NAME = 100, MAX_EMAIL = 254, MIN_PASSWORD = 6, MAX_PASSWORD_BYTES = 72;

  private final AppUserRepository users;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  public AuthController(AppUserRepository users) { this.users = users; }

  @PostMapping("/login") public ResponseEntity<?> login(@RequestBody(required = false) Map<String,Object> body) {
    String email=text(body,"email").toLowerCase(Locale.ROOT), password=text(body,"password");
    if(email.isEmpty()||password.isEmpty()) return error(400,"Email and password are required");
    if(password.getBytes(StandardCharsets.UTF_8).length>MAX_PASSWORD_BYTES) return error(401,"Invalid email or password");
    Optional<AppUser> match=users.findByEmail(email).filter(user -> encoder.matches(password,user.getPasswordHash()));
    return match.<ResponseEntity<?>>map(user -> ResponseEntity.ok(Map.of("success",true,"user",userView(user))))
      .orElseGet(() -> error(401,"Invalid email or password"));
  }

  @PostMapping("/register") public ResponseEntity<?> register(@RequestBody(required = false) Map<String,Object> body) {
    String name=text(body,"name"), email=text(body,"email").toLowerCase(Locale.ROOT), password=text(body,"password"), business=text(body,"businessName"), role=text(body,"role").toUpperCase(Locale.ROOT);
    if(name.isEmpty()) return error(400,"Your name is required");
    if(business.isEmpty()) return error(400,"Business name is required");
    if(name.length()>MAX_NAME||business.length()>MAX_NAME) return error(400,"Name and business name must be "+MAX_NAME+" characters or fewer");
    if(email.length()>MAX_EMAIL||!EMAIL.matcher(email).matches()) return error(400,"Enter a valid email address");
    if(password.length()<MIN_PASSWORD) return error(400,"Password must be at least "+MIN_PASSWORD+" characters");
    if(password.getBytes(StandardCharsets.UTF_8).length>MAX_PASSWORD_BYTES) return error(400,"Password must be "+MAX_PASSWORD_BYTES+" characters or fewer");
    if(!(role.equals("OWNER")||role.equals("SUPPLIER"))) return error(400,"Choose an account type: spaza owner or supplier");
    if(users.existsByEmail(email)) return error(409,"An account with this email already exists");
    try {
      AppUser user=users.saveAndFlush(new AppUser(UUID.randomUUID().toString(),name,email,encoder.encode(password),role,business,false));
      return ResponseEntity.status(201).body(Map.of("success",true,"user",userView(user)));
    } catch (DataIntegrityViolationException e) {
      // Two simultaneous sign-ups with the same email: the unique constraint wins.
      return error(409,"An account with this email already exists");
    }
  }

  private static ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).body(Map.of("error",message));}
  private static String text(Map<String,Object> body,String key){
    Object value=body==null?null:body.get(key);
    return value==null?"":String.valueOf(value).trim();
  }
  private Map<String,Object> userView(AppUser user){return Map.of("id",user.getId(),"name",user.getName(),"email",user.getEmail(),"role",user.getRole(),"shop",user.getBusinessName(),"verified",user.isVerified());}
}
