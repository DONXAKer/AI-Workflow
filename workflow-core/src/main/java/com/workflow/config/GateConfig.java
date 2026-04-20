package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/**
 * Named precondition for a block. All configured gates must evaluate to true before
 * the block is allowed to run; otherwise the run fails with a detailed error.
 *
 * <p>YAML supports two forms:
 * <pre>
 * required_gates:
 *   - "$.deploy_staging.status == 'success'"           # short form (expr only)
 *   - name: smoke_ok                                    # structured form
 *     expr: "$.acceptance.status == 'passed'"
 *     description: "Smoke tests must pass before prod"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = GateConfig.GateDeserializer.class)
public class GateConfig {

    private String name;
    private String expr;
    private String description;

    public GateConfig() {}

    public GateConfig(String name, String expr, String description) {
        this.name = name;
        this.expr = expr;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExpr() { return expr; }
    public void setExpr(String expr) { this.expr = expr; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String displayName() {
        return name != null && !name.isBlank() ? name : expr;
    }

    public static class GateDeserializer extends JsonDeserializer<GateConfig> {
        @Override
        public GateConfig deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isTextual()) {
                return new GateConfig(null, node.asText(), null);
            }
            GateConfig cfg = new GateConfig();
            if (node.hasNonNull("name")) cfg.name = node.get("name").asText();
            if (node.hasNonNull("expr")) cfg.expr = node.get("expr").asText();
            if (node.hasNonNull("description")) cfg.description = node.get("description").asText();
            return cfg;
        }
    }
}
