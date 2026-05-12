package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Computes deterministic fingerprints over block inputs/config and looks up prior cache-eligible
 * BlockOutput rows that share the same fingerprint. Lets identical re-runs of cacheable blocks
 * (analysis, task_md_input, plan) skip the LLM call entirely.
 *
 * <p>Scope partitioning: {@code <projectSlug>:<blockType>:<blockId>}. Including blockId makes
 * renaming a YAML block id a deliberate cache-bust signal; cross-project hits never collide.
 *
 * <p>Fingerprint inputs:
 * <ul>
 *   <li>block type + id
 *   <li>{@link BlockConfig#getConfig()} (the YAML {@code config:} map, sorted recursively)
 *   <li>resolved agent settings (model, temperature, systemPrompt)
 *   <li>filtered inputs map (volatile {@code _}-prefixed keys dropped)
 *   <li>run-level requirement text
 *   <li>SHA-256 of {@code task.md} content (resolved from {@code inputs.task_file})
 *   <li>SHA-256 of {@code <workingDir>/CLAUDE.md}
 * </ul>
 */
@Service
public class BlockCacheService {

    private static final Logger log = LoggerFactory.getLogger(BlockCacheService.class);

    private final ObjectMapper objectMapper;
    private final BlockOutputRepository repository;

    @Autowired(required = false)
    private ProjectRepository projectRepository;

    @Autowired
    public BlockCacheService(ObjectMapper objectMapper, BlockOutputRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    public String scopeOf(PipelineRun run, BlockConfig cfg) {
        String project = run != null && run.getProjectSlug() != null ? run.getProjectSlug() : "default";
        return project + ":" + cfg.getBlock() + ":" + cfg.getId();
    }

    public String computeKey(BlockConfig cfg, Map<String, Object> inputs, PipelineRun run) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("blockType", cfg.getBlock());
            canonical.put("blockId", cfg.getId());
            canonical.put("config", canonicalize(cfg.getConfig()));
            canonical.put("agent", agentFingerprint(cfg.getAgent()));
            canonical.put("inputs", stripVolatile(inputs));
            canonical.put("requirement", run != null ? run.getRequirement() : null);
            canonical.put("taskMdHash", taskMdHash(inputs));
            canonical.put("claudeMdHash", claudeMdHash(run));
            String json = objectMapper.writeValueAsString(canonical);
            return sha256Hex(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to compute cache key for block {}: {}", cfg.getId(), e.getMessage());
            return null;
        }
    }

    public Optional<BlockOutput> lookup(String scope, String key) {
        if (scope == null || key == null) return Optional.empty();
        try {
            List<BlockOutput> hits = repository.findCacheCandidates(scope, key, PageRequest.of(0, 1));
            return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
        } catch (Exception e) {
            log.warn("Cache lookup failed for scope={} key={}: {}", scope, key, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Object canonicalize(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalize(e.getValue()));
            }
            return sorted;
        }
        if (node instanceof List<?> l) {
            return l.stream().map(this::canonicalize).toList();
        }
        return node;
    }

    private Map<String, Object> agentFingerprint(AgentConfig agent) {
        if (agent == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", agent.getModel());
        m.put("tier", agent.getTier());
        m.put("temperature", agent.getTemperatureOrDefault());
        m.put("maxTokens", agent.getMaxTokensOrDefault());
        m.put("systemPrompt", agent.getSystemPrompt());
        return m;
    }

    /** Drops keys that change every run (timestamps, dry-run flags, etc.) so they don't bust the cache. */
    private Map<String, Object> stripVolatile(Map<String, Object> inputs) {
        if (inputs == null) return Map.of();
        Map<String, Object> filtered = new TreeMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            String k = e.getKey();
            if (k == null || k.startsWith("_")) continue;
            filtered.put(k, canonicalize(e.getValue()));
        }
        return filtered;
    }

    /** Hashes the resolved task.md file content (so file edits invalidate the cache even when path matches). */
    private String taskMdHash(Map<String, Object> inputs) {
        if (inputs == null) return null;
        Object tf = inputs.get("task_file");
        if (tf == null) return null;
        try {
            Path p = Path.of(tf.toString());
            if (!Files.isRegularFile(p)) return null;
            return sha256Hex(Files.readAllBytes(p));
        } catch (Exception e) {
            return null;
        }
    }

    private String claudeMdHash(PipelineRun run) {
        if (projectRepository == null) return null;
        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) return null;
        try {
            Project project = projectRepository.findBySlug(slug).orElse(null);
            if (project == null || project.getWorkingDir() == null || project.getWorkingDir().isBlank()) return null;
            Path p = Path.of(project.getWorkingDir(), "CLAUDE.md");
            if (!Files.isRegularFile(p)) return null;
            return sha256Hex(Files.readAllBytes(p));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
