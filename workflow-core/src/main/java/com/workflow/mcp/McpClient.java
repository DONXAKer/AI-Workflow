package com.workflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP MCP client speaking JSON-RPC 2.0 (MCP spec 2025-06-18).
 *
 * <p>Supports the three operations the agentic loop needs: {@code initialize} (once per
 * session), {@code tools/list}, {@code tools/call}. The MCP session id returned by the
 * server in the {@code Mcp-Session-Id} header is captured and replayed on subsequent
 * calls to that same server.
 *
 * <p>One {@link McpClient} bean is shared across all blocks; per-server state (session
 * id, initialised flag) is cached by {@link McpServer#getId()}. SSE streaming, sampling,
 * and roots are out of scope — agent loop only needs request/response semantics.
 *
 * <p>Out-of-band failures (network, JSON-RPC error envelope) raise {@link RuntimeException}
 * with the server name and method, so {@link DefaultToolExecutor} can convert them to
 * {@code is_error:true} results for the LLM to react to.
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<Long, String> sessionIds = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> initialized = new ConcurrentHashMap<>();

    @Autowired
    public McpClient(ObjectMapper mapper) { this.mapper = mapper; }

    /** Returns the tools the server advertises via {@code tools/list}. */
    public java.util.List<ToolDef> listTools(McpServer server) {
        ensureInitialized(server);
        JsonNode result = rpc(server, "tools/list", null);
        java.util.List<ToolDef> out = new java.util.ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String name = t.path("name").asText("");
                if (name.isBlank()) continue;
                out.add(new ToolDef(
                    name,
                    t.path("description").asText(""),
                    t.path("inputSchema").isMissingNode() ? null : t.path("inputSchema")));
            }
        }
        return out;
    }

    /**
     * Invokes a tool on the server. Concatenates {@code text}-typed content blocks
     * from the response; falls back to the raw JSON if no text content is present.
     */
    public String callTool(McpServer server, String toolName, JsonNode args) {
        ensureInitialized(server);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", args != null && args.isObject() ? args : mapper.createObjectNode());
        JsonNode result = rpc(server, "tools/call", params);
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : content) {
                if ("text".equals(c.path("type").asText(""))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.path("text").asText(""));
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        // No text content blocks (binary / structured / error) — surface the raw result.
        return result.toString();
    }

    private void ensureInitialized(McpServer server) {
        if (Boolean.TRUE.equals(initialized.get(server.getId()))) return;
        synchronized (this) {
            if (Boolean.TRUE.equals(initialized.get(server.getId()))) return;
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "ai-workflow");
            clientInfo.put("version", "1.0");
            rpc(server, "initialize", params);
            // Per MCP spec the client must send notifications/initialized after the
            // initialize response. Notifications carry no id and expect no reply.
            postEnvelope(server, buildEnvelope("notifications/initialized", null, -1));
            initialized.put(server.getId(), Boolean.TRUE);
            log.info("MCP server '{}' initialised at {}", server.getName(), server.getUrl());
        }
    }

    private JsonNode rpc(McpServer server, String method, JsonNode params) {
        int id = seq.getAndIncrement();
        ObjectNode envelope = buildEnvelope(method, params, id);
        JsonNode response = postEnvelope(server, envelope);
        if (response.has("error")) {
            JsonNode err = response.get("error");
            throw new RuntimeException("MCP " + server.getName() + " " + method
                + " failed: " + err.path("code").asInt(0) + " " + err.path("message").asText(""));
        }
        return response.path("result");
    }

    private ObjectNode buildEnvelope(String method, JsonNode params, int id) {
        ObjectNode env = mapper.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id >= 0) env.put("id", id);
        env.put("method", method);
        if (params != null) env.set("params", params);
        return env;
    }

    private JsonNode postEnvelope(McpServer server, ObjectNode envelope) {
        return postEnvelope(server, envelope, /*allowSessionRetry*/ true);
    }

    private JsonNode postEnvelope(McpServer server, ObjectNode envelope, boolean allowSessionRetry) {
        WebClient client = WebClient.builder()
            .baseUrl(server.getUrl())
            .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
        final int[] statusBox = {0};
        String body;
        try {
            body = client.post()
                .headers(h -> {
                    String sid = sessionIds.get(server.getId());
                    if (sid != null && !sid.isEmpty()) h.set("Mcp-Session-Id", sid);
                })
                .bodyValue(envelope)
                .exchangeToMono(resp -> {
                    statusBox[0] = resp.statusCode().value();
                    String sid = resp.headers().asHttpHeaders().getFirst("Mcp-Session-Id");
                    if (sid != null && !sid.isEmpty()) sessionIds.put(server.getId(), sid);
                    return resp.bodyToMono(String.class).defaultIfEmpty("");
                })
                .timeout(RPC_TIMEOUT)
                .block();
        } catch (Exception e) {
            throw new RuntimeException("MCP " + server.getName() + " transport failed: " + e.getMessage(), e);
        }
        int status = statusBox[0];
        if (body == null) body = "";
        // 4xx + non-JSON body indicates a session/protocol problem. The streamable
        // HTTP transport responds with text/plain "Invalid Mcp-Session-Id" when the
        // server has rebooted (in-memory session map lost). Drop our cached state
        // and retry once with a fresh initialize handshake.
        if (status >= 400 && status < 500 && allowSessionRetry && !looksLikeJson(body)) {
            String method = envelope.path("method").asText("");
            String preview = body.length() > 200 ? body.substring(0, 200) : body;
            // 421 = persistent transport-level reject (host header, protocol). Do NOT
            // retry — server config is wrong, retry will produce the same failure and
            // we'd recurse forever (ensureInitialized → postEnvelope → 421 → ...).
            if (status == 421) {
                throw new RuntimeException("MCP " + server.getName() + " HTTP 421 on '"
                    + method + "' — server rejected transport: " + preview);
            }
            // 400/403/404 with non-JSON typically means stale session id (server rebooted).
            // Drop cache and retry once; do not re-enter ensureInitialized from inside
            // initialize itself (recursion guard via "initialize".equals(method)).
            log.warn("MCP server '{}' returned HTTP {} on '{}' with non-JSON body — invalidating session and retrying. body[0..200]={}",
                server.getName(), status, method, preview);
            reset(server);
            if (!"initialize".equals(method)) {
                ensureInitialized(server);
            }
            return postEnvelope(server, envelope, /*allowSessionRetry*/ false);
        }
        if (body.isBlank()) return mapper.createObjectNode();
        // SSE-framed response: strip "event:" / ":..." lines and concatenate "data:" payloads.
        String payload = body;
        if (body.startsWith("event:") || body.startsWith("data:") || body.startsWith(":")) {
            StringBuilder sb = new StringBuilder();
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) sb.append(line.substring(5).trim());
            }
            payload = sb.toString();
            if (payload.isBlank()) return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(payload);
        } catch (Exception e) {
            String preview = payload.length() > 300 ? payload.substring(0, 300) : payload;
            throw new RuntimeException("MCP " + server.getName() + " transport failed: HTTP " + status
                + " unparseable body: " + e.getMessage() + " | preview: " + preview, e);
        }
    }

    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.stripLeading();
        return !t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[' || t.startsWith("event:") || t.startsWith("data:"));
    }

    /** Drops cached session/initialised state — call from admin UI to force re-handshake. */
    public void reset(McpServer server) {
        if (server == null || server.getId() == null) return;
        sessionIds.remove(server.getId());
        initialized.remove(server.getId());
    }

    /** Tool descriptor returned by {@code tools/list}. {@code inputSchema} may be null. */
    public record ToolDef(String name, String description, JsonNode inputSchema) {}
}
