package com.workflow.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonExtractor}. Cover the failure modes weak/cloud models actually
 * produce: clean JSON, prose-wrapped, markdown-fenced, braces inside string values,
 * unescaped control chars, and the anchor-not-found fallback.
 */
class JsonExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cleanJson_parsesDirectly() throws Exception {
        Map<String, Object> r = JsonExtractor.extractObject(
            "{\"summary\":\"ok\",\"score\":7}", "summary", mapper);
        assertEquals("ok", r.get("summary"));
        assertEquals(7, r.get("score"));
    }

    @Test
    void proseWrappedJson_extractsObject() throws Exception {
        String text = "Вот результат анализа:\n{\"summary\":\"done\"}\nГотово.";
        Map<String, Object> r = JsonExtractor.extractObject(text, "summary", mapper);
        assertEquals("done", r.get("summary"));
    }

    @Test
    void markdownFenced_stripsAndParses() throws Exception {
        String text = "```json\n{\"summary\":\"fenced\"}\n```";
        Map<String, Object> r = JsonExtractor.extractObject(text, "summary", mapper);
        assertEquals("fenced", r.get("summary"));
    }

    @Test
    void bracesInsideEvidenceString_anchorPicksRightObject() throws Exception {
        // The anchor strategy must walk back to the enclosing '{' that owns the key,
        // not be fooled by '{' inside the evidence snippet.
        String text = "{\"verification_results\":[{\"id\":\"dod-1\",\"passed\":true,"
            + "\"evidence\":\"see code: if (x) { return new Foo(); }\"}]}";
        Map<String, Object> r = JsonExtractor.extractObject(text, "verification_results", mapper);
        assertTrue(r.containsKey("verification_results"));
        List<?> results = (List<?>) r.get("verification_results");
        assertEquals(1, results.size());
    }

    @Test
    void unescapedControlChars_lenientParserRecovers() throws Exception {
        // Raw newline inside a string value — strict JSON rejects, lenient accepts.
        String text = "{\"summary\":\"line one\nline two\"}";
        Map<String, Object> r = JsonExtractor.extractObject(text, "summary", mapper);
        assertTrue(r.get("summary").toString().contains("line one"));
    }

    @Test
    void anchorNotFound_fallsBackToOutermostBraces() throws Exception {
        // No "summary" key present — strategy 1 misses, strategy 2 grabs the object.
        String text = "noise {\"other\":\"value\"} trailing";
        Map<String, Object> r = JsonExtractor.extractObject(text, "summary", mapper);
        assertEquals("value", r.get("other"));
    }

    @Test
    void nullAnchor_stillExtracts() throws Exception {
        Map<String, Object> r = JsonExtractor.extractObject(
            "prefix {\"k\":1} suffix", null, mapper);
        assertEquals(1, r.get("k"));
    }

    @Test
    void totallyUnparseable_throws() {
        assertThrows(Exception.class, () ->
            JsonExtractor.extractObject("this is not json at all", "summary", mapper));
    }

    @Test
    void nullText_throws() {
        assertThrows(Exception.class, () ->
            JsonExtractor.extractObject(null, "summary", mapper));
    }
}
