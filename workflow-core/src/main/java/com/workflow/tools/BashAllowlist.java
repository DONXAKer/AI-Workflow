package com.workflow.tools;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enforces Claude-Code-style {@code Bash(...)} allowlist patterns.
 *
 * <p>Block YAML carries a list of patterns such as:
 * <pre>
 * - Bash(git *)
 * - Bash(gradle test)
 * - Bash(npm run test:*)
 * </pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>The outer {@code Bash(...)} wrapper is stripped; what's inside is the glob.</li>
 *   <li>{@code *} inside the glob matches any sequence of characters (including spaces).</li>
 *   <li>The match is anchored: the whole trimmed command must match the whole pattern.</li>
 *   <li>Entries not wrapped in {@code Bash(...)} are tolerated and treated as bare globs
 *       — useful in tests.</li>
 * </ul>
 *
 * <p>An empty allowlist blocks every bash invocation. This is deliberate: the only way
 * an agent runs shell commands is by the block author opting in via YAML.
 */
public final class BashAllowlist {

    private BashAllowlist() {}

    public static boolean matches(String command, List<String> allowlist) {
        if (command == null || command.isBlank()) return false;
        if (allowlist == null || allowlist.isEmpty()) return false;
        String trimmed = command.trim();
        for (String rawPattern : allowlist) {
            if (toRegex(rawPattern).matcher(trimmed).matches()) return true;
        }
        return false;
    }

    public static void assertMatch(String command, List<String> allowlist) {
        if (command == null || command.isBlank()) {
            throw new ToolInvocationException("empty bash command");
        }
        if (allowlist == null || allowlist.isEmpty()) {
            throw new ToolInvocationException(
                "bash allowlist is empty — no bash commands permitted for this block");
        }
        String trimmed = command.trim();
        for (String rawPattern : allowlist) {
            Pattern regex = toRegex(rawPattern);
            if (regex.matcher(trimmed).matches()) return;
        }
        throw new ToolInvocationException(
            "bash command not in allowlist: '" + trimmed + "' — allowed patterns: "
                + allowlist.stream().collect(Collectors.joining(", ")));
    }

    static Pattern toRegex(String rawPattern) {
        String inner = rawPattern.trim();
        if (inner.startsWith("Bash(") && inner.endsWith(")")) {
            inner = inner.substring(5, inner.length() - 1);
        }
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}
