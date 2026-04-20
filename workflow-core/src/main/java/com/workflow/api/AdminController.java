package com.workflow.api;

import com.workflow.core.KillSwitch;
import com.workflow.core.KillSwitchService;
import com.workflow.security.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private AuditService auditService;

    @GetMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> getState() {
        return ResponseEntity.ok(toDto(killSwitchService.current()));
    }

    @PostMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Object> request) {
        boolean active = Boolean.TRUE.equals(request.get("active"));
        String reason = (String) request.getOrDefault("reason", "");
        boolean cancelActive = Boolean.TRUE.equals(request.get("cancelActive"));
        String actor = currentActor();

        KillSwitch ks;
        if (active) {
            ks = killSwitchService.activate(reason, actor, cancelActive);
            auditService.record("KILL_SWITCH_ACTIVATE", "system", "kill-switch", Map.of(
                "reason", reason,
                "cancelActive", cancelActive));
        } else {
            ks = killSwitchService.deactivate(actor);
            auditService.record("KILL_SWITCH_DEACTIVATE", "system", "kill-switch", Map.of());
        }
        return ResponseEntity.ok(toDto(ks));
    }

    private Map<String, Object> toDto(KillSwitch ks) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("active", ks.isActive());
        dto.put("reason", ks.getReason());
        dto.put("activatedBy", ks.getActivatedBy());
        dto.put("activatedAt", ks.getActivatedAt());
        return dto;
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
