package com.workflow.api;

import com.workflow.config.EntryPointConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.config.TriggerConfig;
import com.workflow.core.EntryPointResolver;
import com.workflow.core.PipelineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Accepts webhook events from task trackers (YouTrack/Jira/Linear/etc.) and starts
 * pipeline runs for every configured trigger that matches the event.
 *
 * <p>Provider-specific parsing and HMAC signature verification are stubbed — they will
 * land in срез 3 together with the {@code TaskTracker} abstraction. For now, callers
 * POST a normalized JSON body:
 * <pre>
 * {
 *   "provider": "youtrack",
 *   "event": "status_change",
 *   "issue_id": "PROJ-42",
 *   "data": { "new_status": "Ready for Dev", ... }
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private PipelineRunner pipelineRunner;

    @Autowired
    private PipelineConfigLoader pipelineConfigLoader;

    @Autowired
    private EntryPointResolver entryPointResolver;

    @Value("${workflow.config-dir:./config}")
    private String configDir;

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String provider,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody Map<String, Object> payload) {

        // TODO: provider-specific HMAC signature verification (reject if invalid)
        // TODO: provider-specific parser that maps raw tracker payloads onto the normalized shape below

        String event = stringOr(payload.get("event"), null);
        String issueId = stringOr(payload.get("issue_id"), null);
        if (issueId == null) issueId = stringOr(payload.get("issueId"), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.get("data") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Collections.emptyMap();

        if (issueId == null || event == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "webhook payload must contain 'event' and 'issue_id' (direct or via normalized parser)"));
        }

        List<Path> configs = pipelineConfigLoader.listConfigs(Paths.get(configDir));
        List<Map<String, Object>> triggered = new ArrayList<>();

        for (Path configPath : configs) {
            PipelineConfig config;
            try {
                config = pipelineConfigLoader.load(configPath);
            } catch (Exception e) {
                log.warn("Skipping unreadable config {}: {}", configPath, e.getMessage());
                continue;
            }
            for (TriggerConfig trigger : config.getTriggers()) {
                if (!matches(trigger, provider, event, data)) continue;

                try {
                    UUID runId = UUID.randomUUID();
                    startRunFromTrigger(config, configPath, trigger, issueId, runId);
                    triggered.add(Map.of(
                        "runId", runId.toString(),
                        "configPath", configPath.toString(),
                        "triggerId", trigger.getId() != null ? trigger.getId() : ""));
                } catch (Exception e) {
                    log.error("Failed to start run for trigger '{}' in {}: {}",
                        trigger.getId(), configPath, e.getMessage(), e);
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", provider);
        response.put("event", event);
        response.put("issueId", issueId);
        response.put("triggered", triggered);
        response.put("count", triggered.size());
        return ResponseEntity.ok(response);
    }

    private boolean matches(TriggerConfig trigger, String provider, String event, Map<String, Object> data) {
        if (!trigger.isEnabled()) return false;
        if (!"webhook".equalsIgnoreCase(trigger.getType())) return false;
        if (trigger.getProvider() != null && !trigger.getProvider().equalsIgnoreCase(provider)) return false;
        if (trigger.getEvent() != null && !trigger.getEvent().equalsIgnoreCase(event)) return false;
        for (Map.Entry<String, String> cond : trigger.getConditions().entrySet()) {
            Object actual = data.get(cond.getKey());
            if (actual == null) return false;
            if (!cond.getValue().equals(String.valueOf(actual))) return false;
        }
        return true;
    }

    private void startRunFromTrigger(PipelineConfig config, Path configPath, TriggerConfig trigger,
                                     String issueId, UUID runId) throws Exception {
        Map<String, Object> userInputs = new HashMap<>();
        userInputs.put("youtrackIssue", issueId);
        userInputs.put("issueId", issueId);

        String fromBlock = trigger.getFromBlock();
        Map<String, Map<String, Object>> injections = new HashMap<>();

        if (trigger.getEntryPointId() != null && !trigger.getEntryPointId().isBlank()) {
            EntryPointConfig ep = config.getEntryPoints().stream()
                .filter(e -> trigger.getEntryPointId().equals(e.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Trigger references unknown entry point: " + trigger.getEntryPointId()));
            fromBlock = ep.getFromBlock();
            injections = entryPointResolver.resolveInjections(ep, userInputs, config);
        }

        log.info("Webhook-triggered run: config={}, trigger={}, issue={}, fromBlock={}",
            configPath.getFileName(), trigger.getId(), issueId, fromBlock);

        if (fromBlock != null && !fromBlock.isBlank()) {
            pipelineRunner.runFrom(config, issueId, fromBlock, injections, runId);
        } else {
            pipelineRunner.run(config, issueId, runId);
        }
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
