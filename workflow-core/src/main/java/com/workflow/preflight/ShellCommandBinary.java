package com.workflow.preflight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Best-effort extraction of the executable name from a shell {@code command:} string, for
 * {@link Requirement.Binary} checks. Deliberately heuristic, not a real shell parser:
 *
 * <ul>
 *   <li>first token is the binary (e.g. {@code gradle build} → {@code gradle});</li>
 *   <li>one level of {@code sh -c "<inner>"} / {@code bash -c ...} is unwrapped to the inner head;</li>
 *   <li>if the binary position holds an unresolved {@code ${...}}/{@code $(...)} interpolation
 *       (block outputs are not yet available pre-run) the result is {@code null} — we skip rather
 *       than emit a false "missing binary".</li>
 * </ul>
 */
public final class ShellCommandBinary {

    private ShellCommandBinary() {}

    private static final Set<String> SHELL_WRAPPERS = Set.of("sh", "bash", "zsh", "dash");

    /** @return the executable name, or {@code null} when it cannot be confidently determined. */
    public static String firstBinary(String command) {
        if (command == null) return null;
        String cmd = command.strip();
        if (cmd.isEmpty()) return null;

        List<String> tokens = tokenize(cmd);
        if (tokens.isEmpty()) return null;

        String first = tokens.get(0);
        if (isShellWrapper(first)) {
            int ci = tokens.indexOf("-c");
            if (ci >= 0 && ci + 1 < tokens.size()) {
                List<String> inner = tokenize(tokens.get(ci + 1));
                if (inner.isEmpty()) return null;
                first = inner.get(0);
            } else {
                // sh running a script file or stdin — don't guess.
                return null;
            }
        }

        if (first.isEmpty() || first.contains("${") || first.contains("$(")) return null;
        // Leading env-var assignment (FOO=bar cmd) — skip rather than treat the assignment as a binary.
        if (first.contains("=") && !first.contains("/")) return null;
        return first;
    }

    private static boolean isShellWrapper(String token) {
        String base = token;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        return SHELL_WRAPPERS.contains(base);
    }

    /** Whitespace tokenizer that keeps single/double-quoted segments together (quotes stripped). */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }
}
