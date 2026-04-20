package com.workflow.api;

import com.workflow.llm.LlmCallRepository;
import com.workflow.llm.LlmCostSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@RestController
@RequestMapping("/api/cost")
@PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
public class CostController {

    @Autowired
    private LlmCallRepository llmCallRepository;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        Instant fromInstant = from != null && !from.isBlank()
            ? LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant()
            : Instant.now().minusSeconds(86400L * 30);
        Instant toInstant = to != null && !to.isBlank()
            ? LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            : Instant.now();

        String scope = com.workflow.project.ProjectContext.get();
        List<LlmCostSummary> rows = llmCallRepository.summarizeByModelForProject(fromInstant, toInstant, scope);

        double totalCost = rows.stream().mapToDouble(LlmCostSummary::costUsd).sum();
        long totalCalls = rows.stream().mapToLong(LlmCostSummary::calls).sum();
        long totalIn = rows.stream().mapToLong(LlmCostSummary::tokensIn).sum();
        long totalOut = rows.stream().mapToLong(LlmCostSummary::tokensOut).sum();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("from", fromInstant);
        response.put("to", toInstant);
        response.put("totalCostUsd", totalCost);
        response.put("totalCalls", totalCalls);
        response.put("totalTokensIn", totalIn);
        response.put("totalTokensOut", totalOut);
        response.put("byModel", rows);
        return ResponseEntity.ok(response);
    }
}
