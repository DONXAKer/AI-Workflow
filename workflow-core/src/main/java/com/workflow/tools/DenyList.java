package com.workflow.tools;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * Hardcoded security guardrails that no tool invocation can bypass.
 *
 * <p>Separated into two surfaces:
 * <ul>
 *   <li>{@link #assertWriteAllowed(Path)} — for {@link WriteTool} and {@link EditTool}.
 *       Rejects writes to filenames that commonly hold secrets: {@code .env*},
 *       {@code *.pem}, {@code *.key}, and SSH private keys. Matched on filename, so it
 *       catches the target no matter which directory the agent picked.</li>
 *   <li>{@link #assertBashAllowed(String)} — for {@link BashTool}. Rejects a small set
 *       of command patterns that cannot be undone from inside the workflow: force-push,
 *       hard reset, recursive rm.</li>
 * </ul>
 *
 * <p>These are the floor — block YAML may narrow further via {@code ToolContext}'s bash
 * allowlist, but it cannot widen past these rules.
 */
public final class DenyList {

    private DenyList() {}

    private static final List<PathMatcher> WRITE_DENY_PATTERNS = List.of(
        compile(".env"),
        compile(".env.*"),
        compile("*.pem"),
        compile("*.key"),
        compile("id_rsa"),
        compile("id_rsa.pub"),
        compile("id_dsa"),
        compile("id_ecdsa"),
        compile("id_ed25519"),
        compile("credentials"),
        compile("credentials.json"),
        compile("*.credentials")
    );

    private static final List<String> BASH_DENY_SUBSTRINGS = List.of(
        "git push --force",
        "git push -f",
        "git reset --hard",
        "rm -rf",
        "rm -fr",
        "rm -r -f",
        "rm -f -r",
        "chmod -R",
        "| sh",
        "| bash"
    );

    public static void assertWriteAllowed(Path target) {
        Path filename = target.getFileName();
        if (filename == null) return;
        String name = filename.toString();
        Path nameOnly = Path.of(name);
        for (PathMatcher m : WRITE_DENY_PATTERNS) {
            if (m.matches(nameOnly)) {
                throw new ToolInvocationException(
                    "write denied by security policy: filename '" + name
                        + "' matches hardcoded secret-file pattern");
            }
        }
    }

    public static void assertBashAllowed(String command) {
        if (command == null) return;
        // Normalize so simple evasions collapse to the canonical form we match against:
        //   - backslash-escaped spaces ("rm\ -rf") -> real spaces
        //   - pipe without surrounding spaces ("curl|sh") -> spaced pipe
        //   - repeated whitespace -> single space
        //   - uppercase ("RM -RF") -> lowercase (POSIX sh is case-sensitive so this is
        //     only relevant to the LLM picking a weird form; cheap to cover)
        String normalized = command.trim()
            .replace("\\ ", " ")
            .replace("|", " | ")
            .replaceAll("\\s+", " ")
            .toLowerCase();
        for (String needle : BASH_DENY_SUBSTRINGS) {
            if (normalized.contains(needle)) {
                throw new ToolInvocationException(
                    "bash command denied by security policy: matches '" + needle + "'");
            }
        }
    }

    private static PathMatcher compile(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }
}
