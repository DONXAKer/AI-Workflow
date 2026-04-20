package com.workflow.api;

import com.workflow.security.User;
import com.workflow.security.UserRepository;
import com.workflow.security.UserRole;
import com.workflow.security.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditService auditService;

    @GetMapping
    public List<Map<String, Object>> list() {
        return userRepository.findAll().stream()
            .map(this::toDto)
            .toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        String displayName = str(body.get("displayName"));
        String email = str(body.get("email"));
        String roleRaw = str(body.get("role"));

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        if (password == null || password.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "password must be at least 8 characters"));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User with this username already exists"));
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setRole(parseRole(roleRaw, UserRole.VIEWER));
        user.setEnabled(true);
        userRepository.save(user);

        auditService.record("USER_CREATE", "user", user.getUsername(), Map.of(
            "role", user.getRole().name()));
        return ResponseEntity.ok(toDto(user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (body.containsKey("displayName")) user.setDisplayName(str(body.get("displayName")));
        if (body.containsKey("email")) user.setEmail(str(body.get("email")));
        if (body.containsKey("role")) user.setRole(parseRole(str(body.get("role")), user.getRole()));
        if (body.containsKey("enabled")) user.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("password")) {
            String pw = str(body.get("password"));
            if (pw != null && pw.length() >= 8) {
                user.setPasswordHash(passwordEncoder.encode(pw));
            }
        }
        userRepository.save(user);
        auditService.record("USER_UPDATE", "user", user.getUsername(), Map.of(
            "role", user.getRole().name(),
            "enabled", user.isEnabled()));
        return ResponseEntity.ok(toDto(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        // Guard: don't let an admin delete themselves.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && user.getUsername().equals(auth.getName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the currently logged-in user"));
        }
        // Guard: don't allow removal of the last admin.
        if (user.getRole() == UserRole.ADMIN) {
            long adminCount = userRepository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && u.isEnabled())
                .count();
            if (adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the last enabled admin"));
            }
        }
        userRepository.delete(user);
        auditService.record("USER_DELETE", "user", user.getUsername(), Map.of());
        Map<String, Object> ok = Map.of("success", true);
        return ResponseEntity.ok(ok);
    }

    private UserRole parseRole(String raw, UserRole fallback) {
        if (raw == null) return fallback;
        try {
            return UserRole.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Map<String, Object> toDto(User user) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", user.getId());
        dto.put("username", user.getUsername());
        dto.put("displayName", user.getDisplayName());
        dto.put("email", user.getEmail());
        dto.put("role", user.getRole().name());
        dto.put("enabled", user.isEnabled());
        dto.put("createdAt", user.getCreatedAt());
        dto.put("updatedAt", user.getUpdatedAt());
        // Never expose passwordHash.
        return dto;
    }

    private String str(Object o) {
        return o instanceof String s ? s.trim() : null;
    }
}
