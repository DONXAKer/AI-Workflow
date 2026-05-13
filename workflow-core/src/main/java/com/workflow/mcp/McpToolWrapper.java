package com.workflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.tools.Tool;
import com.workflow.tools.ToolContext;

/**
 * Adapter exposing one remote MCP tool as a local {@link Tool} so the agentic loop
 * (AgentWithToolsBlock + DefaultToolExecutor) can call it without distinguishing
 * native tools from remote ones. Constructed per block invocation by
 * {@link McpToolLoader} from the YAML's {@code mcp_servers: [...]} list.
 *
 * <p>Names are namespaced as {@code mcp_<server>__<tool>} so multiple MCP servers
 * cannot collide with each other or with native tools (Read/Write/Edit/etc.).
 */
public class McpToolWrapper implements Tool {

    private final String exposedName;
    private final String remoteName;
    private final String description;
    private final JsonNode inputSchema;
    private final McpServer server;
    private final McpClient client;

    public McpToolWrapper(String exposedName, String remoteName, String description,
                          JsonNode inputSchema, McpServer server, McpClient client) {
        this.exposedName = exposedName;
        this.remoteName = remoteName;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
        this.server = server;
        this.client = client;
    }

    @Override public String name() { return exposedName; }
    @Override public String description() { return description; }

    @Override
    public ObjectNode inputSchema(ObjectMapper objectMapper) {
        if (inputSchema != null && inputSchema.isObject()) {
            return (ObjectNode) inputSchema.deepCopy();
        }
        // MCP server returned no schema — give the LLM an empty object so the
        // tool slot remains visible. Calls with no args still work over MCP.
        ObjectNode s = objectMapper.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    @Override
    public String execute(ToolContext context, JsonNode input) throws Exception {
        // ToolContext (workingDir, bashAllowlist) is ignored — remote MCP tools run
        // in their own sandbox. The agent's PathScope only governs native tools.
        return client.callTool(server, remoteName, input);
    }
}
