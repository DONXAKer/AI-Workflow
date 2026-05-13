package com.workflow.mcp;

import com.workflow.project.ProjectContext;
import com.workflow.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a YAML {@code mcp_servers: [name, ...]} list to executable {@link Tool}
 * instances that the agentic loop can call. Connects to each enabled server in the
 * current project's scope, fetches the tools/list result, and wraps each remote
 * tool in an {@link McpToolWrapper}.
 *
 * <p>Failures on one MCP server (network down, server crashed) are logged and skipped
 * — the agent still gets to use the rest. A missing server name is logged at INFO,
 * not an error, so YAML drift is visible without breaking the run.
 */
@Component
public class McpToolLoader {

    private static final Logger log = LoggerFactory.getLogger(McpToolLoader.class);

    private final McpServerRepository repository;
    private final McpClient client;

    @Autowired
    public McpToolLoader(McpServerRepository repository, McpClient client) {
        this.repository = repository;
        this.client = client;
    }

    /**
     * @param mcpServerNames names referenced from the block's {@code mcp_servers} config.
     *                       Names are matched against {@link McpServer#getName()} within
     *                       the current project's slug (via {@link ProjectContext}).
     */
    public List<Tool> loadFor(List<String> mcpServerNames) {
        if (mcpServerNames == null || mcpServerNames.isEmpty()) return List.of();
        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) slug = "default";
        List<McpServer> servers = repository.findByProjectSlugAndNameIn(slug, mcpServerNames);
        if (servers.size() < mcpServerNames.size()) {
            // Report which requested names were not found in the registry — common
            // cause is a typo in YAML or running against the wrong project.
            for (String requested : mcpServerNames) {
                boolean present = servers.stream().anyMatch(s -> requested.equals(s.getName()));
                if (!present) {
                    log.info("MCP server '{}' not registered for project '{}' — skipping", requested, slug);
                }
            }
        }

        List<Tool> tools = new ArrayList<>();
        for (McpServer server : servers) {
            if (!server.isEnabled()) {
                log.info("MCP server '{}' disabled — skipping", server.getName());
                continue;
            }
            try {
                List<McpClient.ToolDef> defs = client.listTools(server);
                for (McpClient.ToolDef def : defs) {
                    String exposed = "mcp_" + sanitize(server.getName()) + "__" + def.name();
                    String desc = def.description();
                    if (desc == null || desc.isBlank()) desc = "(no description)";
                    tools.add(new McpToolWrapper(
                        exposed, def.name(),
                        "[MCP " + server.getName() + "] " + desc,
                        def.inputSchema(), server, client));
                }
                log.info("MCP server '{}': contributed {} tool(s) ({})",
                    server.getName(), defs.size(),
                    defs.stream().map(McpClient.ToolDef::name).toList());
            } catch (Exception e) {
                log.warn("MCP server '{}' (URL {}): tool load failed — {}",
                    server.getName(), server.getUrl(), e.getMessage());
            }
        }
        return tools;
    }

    /** Tool names must match {@code [a-z0-9_-]} for OpenAI tool-call schema. */
    private static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }
}
