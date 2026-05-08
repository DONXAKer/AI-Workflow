package com.workflow.core.expr;

import com.workflow.config.PromptContextConfig;
import com.workflow.tools.DenyList;
import com.workflow.tools.ToolInvocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${sh: command}} placeholders in YAML prompt templates by running
 * the command in the project's working directory and substituting its stdout.
 *
 * <p>Only commands matching the global allowlist (from {@link PromptContextConfig})
 * plus any per-block {@code promptContextAllow} are permitted. The hardcoded
 * {@link DenyList} is also applied as a secondary guard. On error the placeholder
 * is replaced by a descriptive sentinel ({@code [sh error: ...]}) — the block
 * never fails because of a missing context snippet.
 */
@Component
public class PromptContextExecutor {

    private static final Logger log = LoggerFactory.getLogger(PromptContextExecutor.class);
    private static final Pattern SH_PATTERN = Pattern.compile("\\$\\{sh:\\s*(.+?)\\}");
    private static final int MAX_OUTPUT_CHARS = 4000;

    private final PromptContextConfig config;

    @Autowired
    public PromptContextExecutor(PromptContextConfig config) {
        this.config = config;
    }

    /**
     * Replaces all {@code ${sh: command}} occurrences in {@code template} with the
     * command's stdout output. Returns the template unchanged when {@code workingDir}
     * is null or the template contains no {@code ${sh:...}} patterns.
     */
    public String expand(String template, Path workingDir, List<String> extraAllow) {
        if (template == null || workingDir == null) return template;
        if (!SH_PATTERN.matcher(template).find()) return template;
        List<String> effectiveAllow = mergeAllowlist(extraAllow);
        Matcher m = SH_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String command = m.group(1).trim();
            String result = execute(command, workingDir, effectiveAllow);
            m.appendReplacement(sb, Matcher.quoteReplacement(result));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String execute(String command, Path workingDir, List<String> allowlist) {
        if (!isAllowed(command, allowlist)) {
            log.warn("prompt-context: command not in allowlist: {}", command);
            return "[sh error: not allowed]";
        }
        try {
            DenyList.assertBashAllowed(command);
        } catch (ToolInvocationException e) {
            log.warn("prompt-context: command denied by security policy: {}", command);
            return "[sh error: not allowed]";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try { process.getInputStream().transferTo(baos); } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("prompt-context: command timed out after {}ms: {}", config.getTimeoutMs(), command);
                return "[sh error: timeout]";
            }
            reader.join(500);

            int exitCode = process.exitValue();
            String output = baos.toString(StandardCharsets.UTF_8);
            if (exitCode != 0) {
                log.warn("prompt-context: command exited {}: {}", exitCode, command);
                return "[sh error: exit " + exitCode + "]";
            }
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[sh error: interrupted]";
        } catch (Exception e) {
            log.warn("prompt-context: command failed: {} — {}", command, e.getMessage());
            return "[sh error: " + e.getMessage() + "]";
        }
    }

    private boolean isAllowed(String command, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) return false;
        String trimmed = command.trim();
        for (String pattern : allowlist) {
            if (matchesGlob(trimmed, pattern.trim())) return true;
        }
        return false;
    }

    /**
     * Glob matching: {@code *} matches any sequence of characters.
     * A pattern without {@code *} matches if the command equals it exactly or
     * starts with it followed by a space (e.g. {@code "git"} matches {@code "git log"}).
     */
    private boolean matchesGlob(String command, String pattern) {
        if (pattern.equals("*")) return true;
        if (!pattern.contains("*")) {
            return command.equals(pattern) || command.startsWith(pattern + " ");
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        regex.append("$");
        return command.matches(regex.toString());
    }

    private List<String> mergeAllowlist(List<String> extraAllow) {
        List<String> merged = new ArrayList<>(config.getAllow());
        if (extraAllow != null) merged.addAll(extraAllow);
        return merged;
    }
}
