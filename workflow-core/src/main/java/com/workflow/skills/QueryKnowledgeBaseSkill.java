package com.workflow.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.knowledge.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skill: semantic search over the project knowledge base.
 */
@Component
public class QueryKnowledgeBaseSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(QueryKnowledgeBaseSkill.class);

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_knowledge_base";
    }

    @Override
    public String getDescription() {
        return "Semantic search over the project knowledge base (documentation, architecture notes, code summaries). Returns the most relevant excerpts.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Natural-language query to search for.");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Number of results to return (default: 5, max: 10).");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String query = String.valueOf(params.get("query"));
        int topK = 5;
        if (params.containsKey("top_k")) {
            try {
                topK = ((Number) params.get("top_k")).intValue();
                topK = Math.min(topK, 10);
            } catch (Exception ignored) {}
        }

        log.debug("query_knowledge_base: '{}' top_k={}", query, topK);
        String result = knowledgeBase.query(query, topK);
        return Map.of("query", query, "result", result != null ? result : "");
    }
}
