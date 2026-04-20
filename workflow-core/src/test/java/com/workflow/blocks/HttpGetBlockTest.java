package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpGetBlockTest {

    private HttpGetBlock block;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        block = new HttpGetBlock();
        ReflectionTestUtils.setField(block, "webClientBuilder", WebClient.builder());
        ReflectionTestUtils.setField(block, "objectMapper", new ObjectMapper());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void route(String path, int status, String body, String contentType,
                       AtomicReference<Map<String, String>> capturedHeaders) {
        server.createContext(path, ex -> {
            if (capturedHeaders != null) {
                Map<String, String> hdrs = new HashMap<>();
                for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
                    if (!e.getValue().isEmpty()) hdrs.put(e.getKey(), e.getValue().get(0));
                }
                capturedHeaders.set(hdrs);
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });
        server.start();
    }

    private BlockConfig cfgWith(Map<String, Object> cfg) {
        BlockConfig bc = new BlockConfig();
        bc.setId("http");
        bc.setBlock("http_get");
        bc.setConfig(cfg);
        return bc;
    }

    @Test
    void fetchesBodyAndStatus() throws Exception {
        route("/hi", 200, "hello world", "text/plain", null);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/hi");

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertEquals(200, out.get("status"));
        assertEquals(true, out.get("success"));
        assertEquals("hello world", out.get("body"));
    }

    @Test
    void sendsConfiguredHeaders() throws Exception {
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        route("/auth", 200, "ok", "text/plain", captured);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/auth");
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer abc123");
        headers.put("X-Trace", "test-run");
        cfg.put("headers", headers);

        block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertNotNull(captured.get());
        assertEquals("Bearer abc123", captured.get().get("Authorization"));
        assertEquals("test-run", captured.get().get("X-trace"));  // HttpServer lowercases
    }

    @Test
    void parsesJsonWhenEnabled() throws Exception {
        route("/api", 200, "{\"count\":3,\"items\":[\"a\",\"b\",\"c\"]}", "application/json", null);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/api");
        cfg.put("parse_json", true);

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertNotNull(out.get("json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) out.get("json");
        assertEquals(3, json.get("count"));
        assertEquals(List.of("a", "b", "c"), json.get("items"));
    }

    @Test
    void parseJsonFailureRecordsError() throws Exception {
        route("/bad", 200, "not json", "text/plain", null);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/bad");
        cfg.put("parse_json", true);

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertNull(out.get("json"));
        assertNotNull(out.get("json_parse_error"));
    }

    @Test
    void nonZeroStatusThrowsByDefault() throws Exception {
        route("/boom", 503, "service unavailable", "text/plain", null);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/boom");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
        assertTrue(ex.getMessage().contains("503"));
    }

    @Test
    void allowNonzeroStatusReturnsResult() throws Exception {
        route("/boom", 404, "nope", "text/plain", null);

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:" + port + "/boom");
        cfg.put("allow_nonzero_status", true);

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertEquals(404, out.get("status"));
        assertEquals(false, out.get("success"));
        assertEquals("nope", out.get("body"));
    }

    @Test
    void missingUrlFails() {
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfgWith(new HashMap<>()), new PipelineRun()));
    }
}
