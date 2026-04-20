package com.workflow.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

/**
 * Central hub for audit events. Keep calls side-effect free: never throw from here into
 * business code (an audit failure shouldn't roll back the user action).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String SYSTEM_ACTOR = "system";

    @Autowired
    private AuditLogRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    public void record(String action, String targetType, String targetId, Map<String, Object> details) {
        recordWithOutcome(action, targetType, targetId, details, "SUCCESS");
    }

    public void recordFailure(String action, String targetType, String targetId, Map<String, Object> details) {
        recordWithOutcome(action, targetType, targetId, details, "FAILURE");
    }

    public void recordWithOutcome(String action, String targetType, String targetId,
                                   Map<String, Object> details, String outcome) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTimestamp(Instant.now());
            entry.setActor(currentActor());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setOutcome(outcome);
            entry.setRemoteAddr(currentRemoteAddr());
            entry.setProjectSlug(com.workflow.project.ProjectContext.get());
            if (details != null && !details.isEmpty()) {
                entry.setDetailsJson(objectMapper.writeValueAsString(details));
            }
            repository.save(entry);
        } catch (Exception e) {
            log.error("Failed to record audit entry ({}/{}): {}", action, targetId, e.getMessage());
        }
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return SYSTEM_ACTOR;
        }
        return auth.getName();
    }

    private String currentRemoteAddr() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
