package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts PR/MR branch references from a task tracker issue (description, handoff comment
 * pattern). Used in intent-verify to resolve which branch to inspect without manual input.
 *
 * <p>If one or more PR/MR links are found, the block returns {@code resolved: true} with the
 * first URL. If nothing is found, it returns {@code resolved: false} — the pipeline should
 * follow with an {@code approval: true} gate so the operator can supply the branch/repo manually.
 *
 * <p>Reads from {@code task_input.issue} (set by the upstream youtrack_input block):
 * <ul>
 *   <li>description — full issue body</li>
 *   <li>customFields — provider-specific map (checked for "Branch" / "MR" custom field keys)</li>
 * </ul>
 *
 * <p>YAML:
 * <pre>
 * - id: branch_resolver
 *   block: branch_resolver
 *   depends_on: [youtrack_input]
 * </pre>
 *
 * <p>Output: {@code resolved}, {@code pr_url}, {@code branch}, {@code all_pr_urls} (list),
 * {@code source} (description | custom_field | not_found).
 */
@Component
public class BranchResolverBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(BranchResolverBlock.class);

    private static final Pattern PR_URL_PATTERN = Pattern.compile(
        "https?://[\\w.-]+(?::\\d+)?/[\\w./-]+/(?:pull|merge_requests|pulls)/\\d+[\\S]*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BRANCH_PATTERN = Pattern.compile(
        "(?:branch[:\\s`]+|agent-sdlc/)([\\w./-]+)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getName() { return "branch_resolver"; }

    @Override
    public String getDescription() {
        return "Извлекает ссылку на PR/MR из описания/комментариев задачи. " +
               "Возвращает resolved=false если ссылка не найдена — ожидается approval gate с ручным вводом.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Branch resolver",
            "input",
            Phase.INTAKE,
            List.of(),
            false,
            Map.of(),
            List.of(
                FieldSchema.output("resolved", "Resolved", "boolean",
                    "true если PR/MR ссылка найдена автоматически."),
                FieldSchema.output("pr_url", "PR/MR URL", "string",
                    "Первая найденная ссылка на PR/MR."),
                FieldSchema.output("branch", "Branch", "string",
                    "Имя ветки, извлечённое из ссылки или описания."),
                FieldSchema.output("all_pr_urls", "All PR/MR URLs", "string_array",
                    "Все найденные PR/MR ссылки (поддержка нескольких репо)."),
                FieldSchema.output("source", "Source", "string",
                    "Откуда извлечена ссылка: description | custom_field | not_found.")
            ),
            30
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run)
            throws Exception {
        // Collect text to search in
        List<String> searchTexts = new ArrayList<>();

        Object taskInputRaw = input.get("task_input");
        Map<String, Object> issue = Map.of();
        if (taskInputRaw instanceof Map<?, ?> ti) {
            Object issueRaw = ((Map<String, Object>) ti).get("issue");
            if (issueRaw instanceof Map<?, ?> iss) {
                issue = (Map<String, Object>) iss;
            }
        }

        String description = stringOrEmpty(issue.get("description"));
        if (!description.isBlank()) searchTexts.add(description);

        // Check customFields for branch/MR keys
        Object cfRaw = issue.get("customFields");
        String customFieldSource = null;
        if (cfRaw instanceof Map<?, ?> cf) {
            for (Map.Entry<?, ?> entry : ((Map<String, Object>) cf).entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                if (key.contains("branch") || key.contains("mr") || key.contains("pr")
                        || key.contains("pull") || key.contains("merge")) {
                    String val = stringOrEmpty(entry.getValue());
                    if (!val.isBlank()) {
                        searchTexts.add(val);
                        customFieldSource = val;
                    }
                }
            }
        }

        // Extract PR/MR URLs
        List<String> allUrls = new ArrayList<>();
        for (String text : searchTexts) {
            Matcher m = PR_URL_PATTERN.matcher(text);
            while (m.find()) {
                String url = m.group().replaceAll("[.,;)]+$", ""); // strip trailing punctuation
                if (!allUrls.contains(url)) allUrls.add(url);
            }
        }

        // Extract branch name from description or URLs
        String branch = null;
        for (String text : searchTexts) {
            Matcher m = BRANCH_PATTERN.matcher(text);
            if (m.find()) {
                branch = m.group(1).trim();
                break;
            }
        }
        // Fallback: extract branch from GitHub/GitLab PR URL path
        if (branch == null && !allUrls.isEmpty()) {
            branch = branchFromUrl(allUrls.get(0));
        }

        String source = allUrls.isEmpty()
            ? "not_found"
            : (customFieldSource != null && allUrls.stream().anyMatch(customFieldSource::contains)
                ? "custom_field" : "description");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resolved", !allUrls.isEmpty());
        out.put("pr_url", allUrls.isEmpty() ? "" : allUrls.get(0));
        out.put("branch", branch != null ? branch : "");
        out.put("all_pr_urls", allUrls);
        out.put("source", source);

        if (!allUrls.isEmpty()) {
            log.info("branch_resolver: found {} PR/MR URL(s) from {}", allUrls.size(), source);
        } else {
            log.info("branch_resolver: no PR/MR URL found — operator must provide branch manually");
        }

        return out;
    }

    private String stringOrEmpty(Object v) {
        return v instanceof String s ? s : (v != null ? v.toString() : "");
    }

    private String branchFromUrl(String url) {
        // GitHub: .../compare/<branch> or just extract nothing meaningful
        // GitLab MR URL doesn't contain branch name — return null
        return null;
    }
}
