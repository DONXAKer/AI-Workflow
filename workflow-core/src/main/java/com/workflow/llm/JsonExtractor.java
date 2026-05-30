package com.workflow.llm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Robust JSON extraction from LLM responses. Weak / local models (qwen3-4b on vLLM/Ollama)
 * and even some cloud models routinely wrap JSON in prose, fence it in markdown, embed
 * stray {@code { }} inside string values (code snippets in "evidence" fields), or emit raw
 * control characters inside strings. A bare {@code objectMapper.readValue(response, ...)}
 * crashes on all of these.
 *
 * <p>This is the unification of the previously-divergent parsers: the depth-matching /
 * key-anchored ladder from {@code AgentVerifyBlock.parseFinalJson} plus the code-fence strip
 * and lenient (unescaped-control-char) fallback from {@code AnalysisBlock}. Strategy ladder:
 * <ol>
 *   <li>strip markdown fences + fix invalid {@code \\`} escapes;</li>
 *   <li>if {@code anchorKey} is given, find {@code "anchorKey"}, walk back to the enclosing
 *       {@code &#123;} and depth-match its {@code &#125;} — robust against braces inside string
 *       values;</li>
 *   <li>outermost {@code &#123; … &#125;} substring (handles prose-wrapped JSON);</li>
 *   <li>lenient parse of the whole text (allows unescaped control chars) — last resort,
 *       throws if even this fails.</li>
 * </ol>
 * Each brace-slice is tried first with the caller's strict mapper, then with a lenient mapper.
 */
public final class JsonExtractor {

    private JsonExtractor() {}

    /** Lenient mapper tolerant of raw newlines/tabs inside JSON string values. */
    private static final ObjectMapper LENIENT = buildLenient();

    private static ObjectMapper buildLenient() {
        JsonFactory f = new JsonFactory();
        f.configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
        return new ObjectMapper(f);
    }

    /**
     * Extracts a {@code Map<String,Object>} from an LLM response.
     *
     * @param text      raw LLM response (may contain prose, fences, embedded braces)
     * @param anchorKey a top-level key expected in the target object (e.g. {@code "summary"},
     *                  {@code "verification_results"}); enables the most robust strategy.
     *                  May be {@code null} to skip the anchored strategy.
     * @param mapper    the caller's ObjectMapper (used for the strict parse attempt)
     * @return the parsed object — never {@code null}
     * @throws Exception if no strategy yields valid JSON
     */
    public static Map<String, Object> extractObject(String text, String anchorKey, ObjectMapper mapper)
            throws Exception {
        return extract(text, anchorKey, new TypeReference<Map<String, Object>>() {}, mapper);
    }

    /**
     * Generic variant — extracts any type describable by {@code type}.
     */
    public static <T> T extract(String text, String anchorKey, TypeReference<T> type, ObjectMapper mapper)
            throws Exception {
        if (text == null) throw new IllegalStateException("empty LLM response");
        String trimmed = stripFences(text.strip());

        // Strategy 1: anchor key → enclosing '{' → depth-matched '}'.
        if (anchorKey != null && !anchorKey.isBlank()) {
            int keyIdx = trimmed.indexOf("\"" + anchorKey + "\"");
            if (keyIdx >= 0) {
                int openBrace = trimmed.lastIndexOf('{', keyIdx);
                if (openBrace >= 0) {
                    String slice = depthMatch(trimmed, openBrace);
                    if (slice != null) {
                        T v = tryParse(slice, type, mapper);
                        if (v != null) return v;
                    }
                }
            }
        }

        // Strategy 2: outermost { … } substring.
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            T v = tryParse(trimmed.substring(firstBrace, lastBrace + 1), type, mapper);
            if (v != null) return v;
        }

        // Strategy 3: lenient parse of the whole text — last resort, propagates failure.
        return LENIENT.readValue(trimmed, type);
    }

    /** Strips a leading/trailing markdown code fence and fixes invalid {@code \\`} escapes. */
    private static String stripFences(String s) {
        String json = s;
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) json = json.substring(start + 1, end).strip();
        }
        return json.replace("\\`", "`");
    }

    /**
     * Returns the substring from {@code openBrace} to its matching closing brace (inclusive),
     * counting nesting depth, or {@code null} if no balanced match exists.
     */
    private static String depthMatch(String text, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return text.substring(openBrace, i + 1);
            }
        }
        return null;
    }

    /** Tries the strict mapper, then the lenient one; returns {@code null} if both fail. */
    private static <T> T tryParse(String slice, TypeReference<T> type, ObjectMapper mapper) {
        try {
            return mapper.readValue(slice, type);
        } catch (Exception ignore) {
            // fall through to lenient
        }
        try {
            return LENIENT.readValue(slice, type);
        } catch (Exception ignore) {
            return null;
        }
    }
}
