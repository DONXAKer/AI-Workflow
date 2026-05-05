package com.workflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.core.BlockOutput;
import com.workflow.core.PipelineRun;
import com.workflow.core.PipelineRunRepository;
import com.workflow.core.RunStatus;
import com.workflow.llm.LlmCall;
import com.workflow.llm.LlmCallRepository;
import com.workflow.tools.ToolCallAudit;
import com.workflow.tools.ToolCallAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RunReportController {

    private static final Logger log = LoggerFactory.getLogger(RunReportController.class);

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("UTC"));

    private static final Map<String, String> BLOCK_LABELS = Map.ofEntries(
        Map.entry("youtrack_input",   "Задача YouTrack"),
        Map.entry("task_md",          "Чтение задачи"),
        Map.entry("task_md_input",    "Чтение задачи"),
        Map.entry("analysis",         "Анализ требований"),
        Map.entry("clarification",    "Уточнение требований"),
        Map.entry("plan",             "Планирование"),
        Map.entry("codegen",          "Генерация кода"),
        Map.entry("code_generation",  "Генерация кода"),
        Map.entry("build_test",       "Сборка и тесты"),
        Map.entry("build",            "Сборка"),
        Map.entry("run_tests",        "Запуск тестов"),
        Map.entry("verify",           "Верификация"),
        Map.entry("verify_code",      "Верификация кода"),
        Map.entry("verify_analysis",  "Верификация анализа"),
        Map.entry("review",           "Ревью кода"),
        Map.entry("ai_review",        "AI-ревью"),
        Map.entry("pr",               "Pull Request"),
        Map.entry("github_pr",        "GitHub PR"),
        Map.entry("gitlab_mr",        "GitLab MR"),
        Map.entry("ci",               "CI/CD"),
        Map.entry("github_actions",   "GitHub Actions"),
        Map.entry("gitlab_ci",        "GitLab CI"),
        Map.entry("deploy",           "Деплой"),
        Map.entry("rollback",         "Откат"),
        Map.entry("release",          "Релиз"),
        Map.entry("release_notes",    "Заметки о релизе"),
        Map.entry("agent_with_tools", "Агент с инструментами"),
        Map.entry("orchestrator",     "Оркестратор"),
        Map.entry("shell_exec",       "Выполнение команд"),
        Map.entry("http_get",         "HTTP запрос"),
        Map.entry("vcs_merge",        "Слияние веток")
    );

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Autowired
    private ToolCallAuditRepository toolCallAuditRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @GetMapping("/runs/{runId}/report")
    public ResponseEntity<byte[]> downloadReport(
            @PathVariable String runId,
            @RequestParam(value = "format", required = false, defaultValue = "html") String format,
            @RequestParam(value = "block", required = false) String block) {
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        PipelineRun run = pipelineRunRepository.findWithCollectionsById(id).orElse(null);
        if (run == null) return ResponseEntity.notFound().build();

        List<LlmCall> llmCalls = llmCallRepository.findByRunIdOrderByTimestampAsc(id);
        List<ToolCallAudit> toolCalls = toolCallAuditRepository.findByRunIdOrderByTimestampAsc(id);

        if ("md".equalsIgnoreCase(format) || "markdown".equalsIgnoreCase(format)) {
            String md = buildMarkdown(run, llmCalls, toolCalls, block);
            byte[] bytes = md.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String suffix = (block != null && !block.isBlank()) ? "-" + sanitizeFilename(block) : "";
            String filename = "run-" + runId.substring(0, 8) + suffix + ".md";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .body(bytes);
        }

        String html = buildHtml(run, llmCalls, toolCalls);
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String filename = "run-report-" + runId.substring(0, 8) + ".html";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
            .body(bytes);
    }

    private static String sanitizeFilename(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML generation
    // ─────────────────────────────────────────────────────────────────────────

    private String buildHtml(PipelineRun run, List<LlmCall> llmCalls, List<ToolCallAudit> toolCalls) {
        // Aggregate LLM stats
        double totalCost = llmCalls.stream().mapToDouble(LlmCall::getCostUsd).sum();
        long totalIn    = llmCalls.stream().mapToLong(LlmCall::getTokensIn).sum();
        long totalOut   = llmCalls.stream().mapToLong(LlmCall::getTokensOut).sum();

        // Group LLM calls per block
        Map<String, List<LlmCall>> llmByBlock = llmCalls.stream()
            .filter(c -> c.getBlockId() != null)
            .collect(Collectors.groupingBy(LlmCall::getBlockId));

        // Group tool calls per block
        Map<String, List<ToolCallAudit>> toolsByBlock = toolCalls.stream()
            .filter(c -> c.getBlockId() != null)
            .collect(Collectors.groupingBy(ToolCallAudit::getBlockId));

        // Build ordered block list from outputs (preserves execution order)
        List<BlockOutput> outputs = run.getOutputs() != null ? run.getOutputs() : List.of();
        List<String> orderedBlocks = outputs.stream()
            .map(BlockOutput::getBlockId)
            .distinct()
            .collect(Collectors.toList());

        // Add failed block if it's not in outputs
        if (run.getCurrentBlock() != null && !orderedBlocks.contains(run.getCurrentBlock())) {
            orderedBlocks.add(run.getCurrentBlock());
        }

        String duration = formatDuration(run.getStartedAt(), run.getCompletedAt());
        String statusLabel  = statusLabel(run.getStatus());
        String statusColor  = statusColor(run.getStatus());
        String statusBg     = statusBg(run.getStatus());

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>Отчёт: ").append(esc(run.getPipelineName())).append("</title>");
        sb.append(CSS);
        sb.append("</head><body>");

        // ── Header ──────────────────────────────────────────────────────────
        sb.append("<div class=\"header\">");
        sb.append("<div class=\"header-top\">");
        sb.append("<h1>").append(esc(run.getPipelineName())).append("</h1>");
        sb.append("<span class=\"badge\" style=\"background:").append(statusBg)
          .append(";color:").append(statusColor).append("\">").append(statusLabel).append("</span>");
        sb.append("</div>");

        sb.append("<div class=\"meta-grid\">");
        metaCell(sb, "ID запуска", run.getId().toString());
        if (run.getProjectSlug() != null && !"default".equals(run.getProjectSlug()))
            metaCell(sb, "Проект", run.getProjectSlug());
        metaCell(sb, "Начало", run.getStartedAt() != null ? DT_FMT.format(run.getStartedAt()) : "—");
        metaCell(sb, "Конец", run.getCompletedAt() != null ? DT_FMT.format(run.getCompletedAt()) : "—");
        metaCell(sb, "Длительность", duration);
        sb.append("</div>");

        if (run.getRequirement() != null && !run.getRequirement().isBlank()) {
            sb.append("<div class=\"requirement\"><b>Требование:</b> ").append(esc(run.getRequirement())).append("</div>");
        }
        if (run.getError() != null && !run.getError().isBlank()) {
            sb.append("<div class=\"error-box\"><b>Ошибка:</b><pre>").append(esc(run.getError())).append("</pre></div>");
        }
        sb.append("</div>"); // header

        // ── Cost summary ────────────────────────────────────────────────────
        if (!llmCalls.isEmpty()) {
            sb.append("<div class=\"section\">");
            sb.append("<h2>Стоимость LLM</h2>");
            sb.append("<div class=\"cost-summary\">");
            costStat(sb, "Итого", String.format("$%.4f", totalCost));
            costStat(sb, "Токены (вход)", String.format("%,d", totalIn));
            costStat(sb, "Токены (выход)", String.format("%,d", totalOut));
            costStat(sb, "Вызовов LLM", String.valueOf(llmCalls.size()));
            sb.append("</div>");

            // Per-block cost table
            sb.append("<table><thead><tr>")
              .append("<th>Блок</th><th>Модель</th><th>Токены вх.</th><th>Токены исх.</th><th>Стоимость</th><th>Вызовов</th>")
              .append("</tr></thead><tbody>");
            for (Map.Entry<String, List<LlmCall>> entry : llmByBlock.entrySet()) {
                List<LlmCall> calls = entry.getValue();
                double cost = calls.stream().mapToDouble(LlmCall::getCostUsd).sum();
                long tIn  = calls.stream().mapToLong(LlmCall::getTokensIn).sum();
                long tOut = calls.stream().mapToLong(LlmCall::getTokensOut).sum();
                String model = calls.stream().map(LlmCall::getModel).findFirst().orElse("—");
                sb.append("<tr>")
                  .append("<td>").append(esc(blockLabel(entry.getKey()))).append("</td>")
                  .append("<td class=\"mono\">").append(esc(model)).append("</td>")
                  .append("<td class=\"num\">").append(String.format("%,d", tIn)).append("</td>")
                  .append("<td class=\"num\">").append(String.format("%,d", tOut)).append("</td>")
                  .append("<td class=\"num cost\">").append(String.format("$%.4f", cost)).append("</td>")
                  .append("<td class=\"num\">").append(calls.size()).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }

        // ── Blocks ──────────────────────────────────────────────────────────
        sb.append("<div class=\"section\"><h2>Блоки выполнения</h2>");

        Map<String, BlockOutput> outputByBlock = outputs.stream()
            .collect(Collectors.toMap(BlockOutput::getBlockId, o -> o, (a, b) -> b));

        for (String blockId : orderedBlocks) {
            BlockOutput bo = outputByBlock.get(blockId);
            boolean completed = run.getCompletedBlocks() != null && run.getCompletedBlocks().contains(blockId);
            boolean isFailed  = blockId.equals(run.getCurrentBlock()) && run.getStatus() == RunStatus.FAILED;
            boolean isSkipped = bo != null && isSkippedOutput(bo.getOutputJson());

            String icon = isFailed ? "❌" : isSkipped ? "⏭" : completed ? "✅" : "⏸";
            String blockLabel = blockLabel(blockId);

            List<LlmCall> blockLlm   = llmByBlock.getOrDefault(blockId, List.of());
            List<ToolCallAudit> blockTools = toolsByBlock.getOrDefault(blockId, List.of());
            double blockCost = blockLlm.stream().mapToDouble(LlmCall::getCostUsd).sum();

            sb.append("<details class=\"block-card").append(isFailed ? " block-failed" : "").append("\" open>");
            sb.append("<summary class=\"block-header\">");
            sb.append("<span class=\"block-icon\">").append(icon).append("</span>");
            sb.append("<span class=\"block-name\">").append(esc(blockLabel)).append("</span>");
            sb.append("<span class=\"block-id mono\">").append(esc(blockId)).append("</span>");
            if (blockCost > 0)
                sb.append("<span class=\"block-cost\">$").append(String.format("%.4f", blockCost)).append("</span>");
            sb.append("</summary>"); // block-header

            sb.append("<div class=\"block-body\">");
            // Input
            if (bo != null && bo.getInputJson() != null) {
                Map<String, Object> input = parseJson(bo.getInputJson());
                if (!input.isEmpty()) {
                    sb.append("<div class=\"io-section\"><div class=\"io-label\">Вход</div>");
                    renderKvTable(sb, input);
                    sb.append("</div>");
                }
            }

            // Output
            if (bo != null && bo.getOutputJson() != null) {
                Map<String, Object> output = parseJson(bo.getOutputJson());
                if (!output.isEmpty()) {
                    sb.append("<div class=\"io-section\"><div class=\"io-label\">Выход</div>");
                    renderKvTable(sb, output);
                    sb.append("</div>");
                }
            }

            // Tool calls
            if (!blockTools.isEmpty()) {
                sb.append("<details class=\"tool-calls\">");
                long errCount = blockTools.stream().filter(ToolCallAudit::isError).count();
                String summary = blockTools.size() + " вызов" + pluralRu(blockTools.size(), "ов", "", "а");
                if (errCount > 0) summary += " · <span class=\"err-count\">" + errCount + " ошиб" + pluralRu((int)errCount, "ок", "ка", "ки") + "</span>";
                sb.append("<summary>🔧 ").append(summary).append("</summary>");
                sb.append("<table class=\"tool-table\"><thead><tr>")
                  .append("<th>#</th><th>Инструмент</th><th>Итер.</th><th>Вход</th><th>Выход</th><th>мс</th>")
                  .append("</tr></thead><tbody>");
                int idx = 1;
                for (ToolCallAudit tc : blockTools) {
                    sb.append("<tr").append(tc.isError() ? " class=\"row-error\"" : "").append(">")
                      .append("<td class=\"num\">").append(idx++).append("</td>")
                      .append("<td><b>").append(esc(tc.getToolName())).append("</b></td>")
                      .append("<td class=\"num\">").append(tc.getIteration() != null ? tc.getIteration() : "—").append("</td>")
                      .append("<td class=\"tool-io\"><pre>").append(esc(truncate(tc.getInputJson(), 400))).append("</pre></td>")
                      .append("<td class=\"tool-io\"><pre>").append(esc(truncate(tc.getOutputText(), 400))).append("</pre></td>")
                      .append("<td class=\"num\">").append(tc.getDurationMs()).append("</td>")
                      .append("</tr>");
                }
                sb.append("</tbody></table></details>");
            }

            sb.append("</div>"); // block-body
            sb.append("</details>"); // block-card
        }

        sb.append("</div>"); // section blocks

        sb.append("<div class=\"footer\">Сгенерировано ").append(DT_FMT.format(Instant.now())).append(" UTC</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void renderKvTable(StringBuilder sb, Map<String, Object> data) {
        // Long-text keys rendered as <pre> after the table
        Set<String> LONG_KEYS = Set.of("stdout", "stderr", "code", "diff", "technical_approach",
            "content", "patch", "description", "summary", "notes", "output", "result");
        List<Map.Entry<String, Object>> simple = new ArrayList<>();
        List<Map.Entry<String, Object>> long_  = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (isLongValue(e.getKey(), e.getValue(), LONG_KEYS)) long_.add(e);
            else simple.add(e);
        }
        if (!simple.isEmpty()) {
            sb.append("<table><tbody>");
            for (Map.Entry<String, Object> e : simple) {
                sb.append("<tr><td class=\"kv-key\">").append(esc(e.getKey())).append("</td>")
                  .append("<td class=\"kv-val\">").append(esc(renderValue(e.getValue()))).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        for (Map.Entry<String, Object> e : long_) {
            sb.append("<div class=\"long-key\">").append(esc(e.getKey())).append("</div>");
            sb.append("<pre class=\"long-val\">").append(esc(renderValue(e.getValue()))).append("</pre>");
        }
    }

    private boolean isLongValue(String key, Object value, Set<String> longKeys) {
        if (longKeys.contains(key)) return true;
        if (value instanceof String s) return s.length() > 120 || s.contains("\n");
        if (value instanceof List || value instanceof Map) return true;
        return false;
    }

    private String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return s;
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining(", "));
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(v);
        } catch (Exception e) {
            return v.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?,?> m) return (Map<String, Object>) m;
        } catch (Exception ignored) {}
        return Map.of();
    }

    private boolean isSkippedOutput(String json) {
        if (json == null) return false;
        return json.contains("\"skipped\"") || json.contains("\"status\":\"skipped\"");
    }

    private static void metaCell(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"meta-cell\"><div class=\"meta-label\">").append(esc(label))
          .append("</div><div class=\"meta-value\">").append(esc(value)).append("</div></div>");
    }

    private static void costStat(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"cost-stat\"><div class=\"cost-val\">").append(value)
          .append("</div><div class=\"cost-label\">").append(esc(label)).append("</div></div>");
    }

    private static String blockLabel(String blockId) {
        return BLOCK_LABELS.getOrDefault(blockId, blockId);
    }

    private static String statusLabel(RunStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case RUNNING            -> "Выполняется";
            case PAUSED_FOR_APPROVAL -> "Ожидает одобрения";
            case COMPLETED          -> "Завершён";
            case FAILED             -> "Ошибка";
            default                 -> s.name();
        };
    }

    private static String statusColor(RunStatus s) {
        if (s == null) return "#94a3b8";
        return switch (s) {
            case COMPLETED          -> "#34d399";
            case FAILED             -> "#f87171";
            case RUNNING            -> "#60a5fa";
            case PAUSED_FOR_APPROVAL -> "#fbbf24";
            default                 -> "#94a3b8";
        };
    }

    private static String statusBg(RunStatus s) {
        if (s == null) return "#1e293b";
        return switch (s) {
            case COMPLETED          -> "#064e3b";
            case FAILED             -> "#450a0a";
            case RUNNING            -> "#1e3a5f";
            case PAUSED_FOR_APPROVAL -> "#451a03";
            default                 -> "#1e293b";
        };
    }

    private static String formatDuration(Instant start, Instant end) {
        if (start == null) return "—";
        Instant finish = end != null ? end : Instant.now();
        Duration d = Duration.between(start, finish);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return String.format("%dч %02dм %02dс", h, m, s);
        if (m > 0) return String.format("%dм %02dс", m, s);
        return String.format("%dс", s);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String pluralRu(int n, String few, String one, String two) {
        int mod10 = n % 10, mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return one;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return two;
        return few;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inline CSS
    // ─────────────────────────────────────────────────────────────────────────

    private static final String CSS = """
        <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        body {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
          background: #0f172a; color: #cbd5e1; font-size: 13px; line-height: 1.6;
          padding: 24px;
        }
        h1 { font-size: 20px; font-weight: 700; color: #f1f5f9; }
        h2 { font-size: 14px; font-weight: 600; color: #94a3b8; text-transform: uppercase;
             letter-spacing: .06em; margin-bottom: 12px; }
        a { color: #60a5fa; }

        .header {
          background: #1e293b; border: 1px solid #334155; border-radius: 12px;
          padding: 20px 24px; margin-bottom: 20px;
        }
        .header-top { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
        .badge {
          font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 20px;
          letter-spacing: .04em;
        }
        .meta-grid { display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 12px; }
        .meta-cell { min-width: 140px; }
        .meta-label { font-size: 10px; text-transform: uppercase; letter-spacing: .06em; color: #64748b; }
        .meta-value { font-size: 13px; color: #e2e8f0; margin-top: 2px; word-break: break-all; }
        .requirement {
          background: #0f172a; border: 1px solid #334155; border-radius: 8px;
          padding: 10px 14px; font-size: 13px; color: #94a3b8; margin-top: 10px;
        }
        .error-box {
          background: #1a0a0a; border: 1px solid #7f1d1d; border-radius: 8px;
          padding: 12px 14px; color: #fca5a5; margin-top: 10px;
        }
        .error-box pre { margin-top: 6px; white-space: pre-wrap; word-break: break-all; font-size: 12px; }

        .section {
          background: #1e293b; border: 1px solid #334155; border-radius: 12px;
          padding: 20px 24px; margin-bottom: 20px;
        }

        .cost-summary { display: flex; flex-wrap: wrap; gap: 20px; margin-bottom: 16px; }
        .cost-stat {
          background: #0f172a; border: 1px solid #334155; border-radius: 8px;
          padding: 12px 20px; text-align: center; min-width: 120px;
        }
        .cost-val { font-size: 20px; font-weight: 700; color: #f1f5f9; }
        .cost-label { font-size: 11px; color: #64748b; margin-top: 2px; }

        table { width: 100%; border-collapse: collapse; font-size: 12px; }
        thead tr { background: #0f172a; }
        th { text-align: left; padding: 7px 10px; color: #64748b; font-weight: 500;
             font-size: 11px; text-transform: uppercase; letter-spacing: .04em;
             border-bottom: 1px solid #334155; }
        td { padding: 6px 10px; border-bottom: 1px solid #1e293b; vertical-align: top; }
        tr:last-child td { border-bottom: none; }
        tr:hover td { background: #1e293b; }

        .num { text-align: right; font-variant-numeric: tabular-nums; }
        .cost { color: #34d399; font-weight: 600; }
        .mono { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 11px; }

        .kv-key { color: #64748b; white-space: nowrap; font-weight: 500; width: 160px; }
        .kv-val { color: #e2e8f0; word-break: break-word; }
        .long-key { font-size: 11px; color: #64748b; text-transform: uppercase;
                    letter-spacing: .04em; margin: 10px 0 4px; }
        .long-val {
          background: #0f172a; border: 1px solid #1e293b; border-radius: 6px;
          padding: 10px 12px; font-family: 'SF Mono', 'Fira Code', monospace;
          font-size: 11px; color: #94a3b8; white-space: pre-wrap; word-break: break-all;
          max-height: 320px; overflow-y: auto; margin-bottom: 8px;
        }

        details.block-card {
          border: 1px solid #334155; border-radius: 10px;
          margin-bottom: 14px; background: #0f172a; overflow: hidden;
        }
        details.block-card.block-failed { border-color: #7f1d1d; background: #1a0505; }
        .block-header {
          display: flex; align-items: center; gap: 10px;
          padding: 12px 16px; cursor: pointer; user-select: none;
          list-style: none; border-bottom: 1px solid transparent;
        }
        details.block-card[open] .block-header { border-bottom-color: #1e293b; }
        .block-header::-webkit-details-marker { display: none; }
        .block-header::before {
          content: '▶'; font-size: 9px; color: #475569;
          transition: transform .15s; flex-shrink: 0;
        }
        details.block-card[open] .block-header::before { transform: rotate(90deg); }
        .block-body { padding: 12px 16px; }
        .block-icon { font-size: 16px; }
        .block-name { font-weight: 600; color: #e2e8f0; font-size: 14px; }
        .block-id { color: #475569; font-size: 11px; margin-left: 4px; }
        .block-cost {
          margin-left: auto; background: #064e3b; color: #34d399; font-weight: 600;
          font-size: 12px; padding: 2px 8px; border-radius: 20px;
        }
        .io-section { margin-bottom: 10px; }
        .io-label {
          font-size: 10px; text-transform: uppercase; letter-spacing: .06em;
          color: #475569; margin-bottom: 6px; font-weight: 600;
        }

        details.tool-calls {
          border: 1px solid #1e293b; border-radius: 8px; overflow: hidden; margin-top: 10px;
        }
        details.tool-calls summary {
          padding: 8px 12px; cursor: pointer; font-size: 12px; color: #94a3b8;
          background: #1e293b; list-style: none; user-select: none;
        }
        details.tool-calls summary::-webkit-details-marker { display: none; }
        details.tool-calls[open] summary { border-bottom: 1px solid #334155; }
        .err-count { color: #f87171; font-weight: 600; }
        .tool-table { background: #0a0f1a; }
        .tool-io pre { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 10px;
                       color: #64748b; white-space: pre-wrap; word-break: break-all; max-width: 320px; }
        .row-error td { background: #1a0505; color: #fca5a5; }
        .row-error .tool-io pre { color: #f87171; }

        .footer {
          text-align: center; color: #334155; font-size: 11px; margin-top: 24px;
        }
        @media print {
          body { background: #fff; color: #111; padding: 0; }
          .header, .section, .block-card { border-color: #ccc; background: #fff; }
          details { display: block; }
          details summary { display: none; }
        }
        </style>
        """;

    // ─────────────────────────────────────────────────────────────────────────
    // Markdown generation
    //
    // Single canonical format consumed both by the download button and by an
    // external LLM scraper via `?format=md`. Per-block extraction (`?block=ID`)
    // returns just the matching `## block` section so a Copy-block-as-MD button
    // produces the same string the full report would contain for that block.
    // ─────────────────────────────────────────────────────────────────────────

    private String buildMarkdown(PipelineRun run, List<LlmCall> llmCalls,
                                  List<ToolCallAudit> toolCalls, String blockFilter) {
        Map<String, List<LlmCall>> llmByBlock = llmCalls.stream()
            .filter(c -> c.getBlockId() != null)
            .collect(Collectors.groupingBy(LlmCall::getBlockId));
        Map<String, List<ToolCallAudit>> toolsByBlock = toolCalls.stream()
            .filter(c -> c.getBlockId() != null)
            .collect(Collectors.groupingBy(ToolCallAudit::getBlockId));

        List<BlockOutput> outputs = run.getOutputs() != null ? run.getOutputs() : List.of();
        List<String> orderedBlocks = outputs.stream()
            .map(BlockOutput::getBlockId).distinct().collect(Collectors.toList());
        if (run.getCurrentBlock() != null && !orderedBlocks.contains(run.getCurrentBlock())) {
            orderedBlocks.add(run.getCurrentBlock());
        }
        Map<String, BlockOutput> outputByBlock = outputs.stream()
            .collect(Collectors.toMap(BlockOutput::getBlockId, o -> o, (a, b) -> b));

        StringBuilder sb = new StringBuilder();

        boolean singleBlock = blockFilter != null && !blockFilter.isBlank();

        if (!singleBlock) {
            // Run header
            sb.append("# Run ").append(run.getId()).append(" — ")
              .append(run.getPipelineName()).append(" (").append(statusLabel(run.getStatus())).append(")\n\n");
            if (run.getStartedAt() != null) {
                sb.append("- **Started:** ").append(run.getStartedAt()).append('\n');
            }
            if (run.getCompletedAt() != null) {
                sb.append("- **Completed:** ").append(run.getCompletedAt()).append('\n');
            }
            sb.append("- **Duration:** ").append(formatDuration(run.getStartedAt(), run.getCompletedAt())).append('\n');
            if (run.getProjectSlug() != null && !"default".equals(run.getProjectSlug())) {
                sb.append("- **Project:** ").append(run.getProjectSlug()).append('\n');
            }
            double totalCost = llmCalls.stream().mapToDouble(LlmCall::getCostUsd).sum();
            if (totalCost > 0) sb.append("- **Total LLM cost:** $").append(String.format("%.4f", totalCost)).append('\n');
            if (!llmCalls.isEmpty()) {
                long tIn = llmCalls.stream().mapToLong(LlmCall::getTokensIn).sum();
                long tOut = llmCalls.stream().mapToLong(LlmCall::getTokensOut).sum();
                sb.append("- **Tokens:** ").append(String.format("%,d", tIn)).append(" in / ")
                  .append(String.format("%,d", tOut)).append(" out\n");
            }
            sb.append('\n');

            if (run.getRequirement() != null && !run.getRequirement().isBlank()) {
                sb.append("**Requirement:** ").append(run.getRequirement()).append("\n\n");
            }
            if (run.getError() != null && !run.getError().isBlank()) {
                sb.append("**Error:**\n```\n").append(run.getError()).append("\n```\n\n");
            }
            sb.append("---\n\n");
        }

        // Blocks
        for (String blockId : orderedBlocks) {
            if (singleBlock && !blockId.equals(blockFilter)) continue;
            renderBlockMd(sb, blockId, outputByBlock.get(blockId),
                llmByBlock.getOrDefault(blockId, List.of()),
                toolsByBlock.getOrDefault(blockId, List.of()));
        }

        if (singleBlock && sb.length() == 0) {
            sb.append("# Block `").append(blockFilter).append("` not found in run\n");
        }

        return sb.toString();
    }

    private void renderBlockMd(StringBuilder sb, String blockId, BlockOutput bo,
                                List<LlmCall> blockLlm, List<ToolCallAudit> blockTools) {
        // Block header with inline metadata
        sb.append("## ").append(blockId);
        String label = blockLabel(blockId);
        if (label != null && !label.equals(blockId)) sb.append(" (").append(label).append(')');
        sb.append("\n\n");

        // Inline metadata line
        List<String> meta = new ArrayList<>();
        if (!blockLlm.isEmpty()) {
            String model = blockLlm.get(0).getModel();
            if (model != null) meta.add("model: " + model);
            String provider = blockLlm.get(0).getProvider() != null ? blockLlm.get(0).getProvider().name() : null;
            if (provider != null) meta.add("provider: " + provider);
            int iters = (int) blockLlm.stream().filter(c -> c.getIteration() > 0)
                .mapToInt(LlmCall::getIteration).distinct().count();
            if (iters > 0) meta.add(iters + " iterations");
            double cost = blockLlm.stream().mapToDouble(LlmCall::getCostUsd).sum();
            if (cost > 0) meta.add(String.format("$%.4f", cost));
            int durMs = blockLlm.stream().mapToInt(LlmCall::getDurationMs).sum();
            if (durMs > 0) meta.add(formatMs(durMs));
        }
        if (!meta.isEmpty()) {
            sb.append("`").append(String.join(" · ", meta)).append("`\n\n");
        }

        // Iterations table (only if there are tool-use iterations)
        if (!blockTools.isEmpty()) {
            Map<Integer, List<ToolCallAudit>> byIter = blockTools.stream()
                .collect(Collectors.groupingBy(t -> t.getIteration() == null ? 0 : t.getIteration()));
            sb.append("### Iterations\n\n");
            sb.append("| Iter | Tools | Errors | Duration |\n");
            sb.append("|------|-------|--------|----------|\n");
            for (Map.Entry<Integer, List<ToolCallAudit>> e : byIter.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).collect(Collectors.toList())) {
                List<ToolCallAudit> calls = e.getValue();
                Map<String, Long> counts = calls.stream()
                    .collect(Collectors.groupingBy(ToolCallAudit::getToolName, Collectors.counting()));
                String toolStr = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(en -> en.getValue() + "×" + en.getKey())
                    .collect(Collectors.joining(", "));
                long errs = calls.stream().filter(ToolCallAudit::isError).count();
                int dur = calls.stream().mapToInt(ToolCallAudit::getDurationMs).sum();
                sb.append("| ").append(e.getKey()).append(" | ").append(toolStr)
                  .append(" | ").append(errs > 0 ? errs : "—")
                  .append(" | ").append(formatMs(dur)).append(" |\n");
            }
            sb.append('\n');
        }

        // Output as structured fields
        if (bo != null && bo.getOutputJson() != null) {
            Map<String, Object> output = parseJson(bo.getOutputJson());
            if (output != null && !output.isEmpty()) {
                sb.append("### Output\n\n");
                renderFieldsMd(sb, output);
            }
        }

        sb.append("\n---\n\n");
    }

    private void renderFieldsMd(StringBuilder sb, Map<String, Object> output) {
        // Render known important fields first, in priority order
        String[] PRIORITY_FIELDS = {
            "title", "feat_id", "complexity", "needs_clarification",
            "as_is", "to_be", "out_of_scope", "acceptance",
            "technical_approach", "affected_components", "acceptance_checklist",
            "goal", "approach", "files_to_touch", "definition_of_done", "tools_to_use",
            "retry_instruction", "issues",
            "passed_items", "failed_items", "verification_results", "pass_threshold",
            "final_text",
            "success", "exit_code", "duration_ms", "stdout", "stderr", "command",
            "_skipped", "reason", "status",
        };
        Set<String> known = new HashSet<>(Arrays.asList(PRIORITY_FIELDS));
        for (String key : PRIORITY_FIELDS) {
            if (output.containsKey(key)) {
                renderFieldMd(sb, key, output.get(key));
            }
        }
        // Unknown fields go to a code block
        Map<String, Object> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : output.entrySet()) {
            if (!known.contains(e.getKey()) && !e.getKey().startsWith("_") && e.getValue() != null) {
                unknown.put(e.getKey(), e.getValue());
            }
        }
        if (!unknown.isEmpty()) {
            sb.append("**Other fields:**\n```json\n");
            try {
                sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(unknown));
            } catch (Exception ex) {
                sb.append(unknown.toString());
            }
            sb.append("\n```\n\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void renderFieldMd(StringBuilder sb, String key, Object value) {
        if (value == null) return;
        String label = fieldLabel(key);
        // Lists
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return;
            sb.append("**").append(label).append(":**\n");
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<String, Object> obj = (Map<String, Object>) item;
                    String priority = obj.get("priority") != null ? obj.get("priority").toString() : null;
                    String status = obj.get("status") != null ? obj.get("status").toString() : null;
                    Object main = obj.getOrDefault("item",
                        obj.getOrDefault("requirement",
                            obj.getOrDefault("description",
                                obj.getOrDefault("text", obj.values().iterator().next()))));
                    sb.append("- ");
                    if (priority != null) sb.append('[').append(priority).append("] ");
                    if ("pass".equals(status)) sb.append("✓ ");
                    else if ("fail".equals(status)) sb.append("✗ ");
                    sb.append(main).append('\n');
                } else {
                    sb.append("- ").append(item).append('\n');
                }
            }
            sb.append('\n');
            return;
        }
        // Multiline strings
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return;
            if (text.contains("\n") || text.length() > 80) {
                sb.append("**").append(label).append(":**\n```\n").append(text).append("\n```\n\n");
            } else {
                sb.append("- **").append(label).append(":** ").append(text).append('\n');
            }
            return;
        }
        // Booleans
        if (value instanceof Boolean) {
            sb.append("- **").append(label).append(":** ").append(((Boolean) value) ? "✓" : "✗").append('\n');
            return;
        }
        // Numbers / fallback
        sb.append("- **").append(label).append(":** ").append(value).append('\n');
    }

    private static String fieldLabel(String key) {
        return switch (key) {
            case "as_is" -> "Как сейчас";
            case "to_be" -> "Как надо";
            case "out_of_scope" -> "Вне scope";
            case "acceptance" -> "Критерии приёмки";
            case "acceptance_checklist" -> "Acceptance checklist";
            case "technical_approach" -> "Технический подход";
            case "affected_components" -> "Затрагиваемые компоненты";
            case "definition_of_done" -> "Definition of Done";
            case "files_to_touch" -> "Files to touch";
            case "tools_to_use" -> "Tools to use";
            case "retry_instruction" -> "Retry instruction";
            case "passed_items" -> "Passed";
            case "failed_items" -> "Failed";
            case "verification_results" -> "Verification results";
            case "pass_threshold" -> "Pass threshold";
            case "final_text" -> "Result";
            case "exit_code" -> "Exit code";
            case "duration_ms" -> "Duration (ms)";
            case "_skipped" -> "Skipped";
            default -> key;
        };
    }

    private static String formatMs(int ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        long m = s / 60;
        if (m == 0) return s + "s";
        return m + "m " + (s % 60) + "s";
    }
}
