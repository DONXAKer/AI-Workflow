package com.workflow.api;

import com.workflow.security.audit.AuditLog;
import com.workflow.security.audit.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        size = Math.min(size, 200);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));

        String currentProject = com.workflow.project.ProjectContext.get();
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("projectSlug"), currentProject));
            if (actor != null && !actor.isBlank()) predicates.add(cb.equal(root.get("actor"), actor));
            if (action != null && !action.isBlank()) predicates.add(cb.equal(root.get("action"), action));
            if (targetType != null && !targetType.isBlank()) predicates.add(cb.equal(root.get("targetType"), targetType));
            if (targetId != null && !targetId.isBlank()) predicates.add(cb.equal(root.get("targetId"), targetId));
            if (outcome != null && !outcome.isBlank()) predicates.add(cb.equal(root.get("outcome"), outcome));
            if (from != null && !from.isBlank()) {
                try {
                    Instant fromInstant = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant();
                    predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), fromInstant));
                } catch (Exception ignored) {}
            }
            if (to != null && !to.isBlank()) {
                try {
                    Instant toInstant = LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    predicates.add(cb.lessThan(root.get("timestamp"), toInstant));
                } catch (Exception ignored) {}
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLog> result = auditLogRepository.findAll(spec, pageable);

        List<Map<String, Object>> content = result.getContent().stream().map(a -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", a.getId());
            dto.put("timestamp", a.getTimestamp());
            dto.put("actor", a.getActor());
            dto.put("action", a.getAction());
            dto.put("targetType", a.getTargetType());
            dto.put("targetId", a.getTargetId());
            dto.put("outcome", a.getOutcome());
            dto.put("detailsJson", a.getDetailsJson());
            dto.put("remoteAddr", a.getRemoteAddr());
            return dto;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("totalElements", result.getTotalElements());
        response.put("totalPages", result.getTotalPages());
        response.put("page", result.getNumber());
        response.put("size", result.getSize());
        return ResponseEntity.ok(response);
    }
}
