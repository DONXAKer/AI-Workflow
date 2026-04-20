package com.workflow.knowledge;

import org.springframework.stereotype.Service;

@Service
public class NoOpKnowledgeBase implements KnowledgeBase {

    @Override
    public String query(String query, int nResults) {
        return "";
    }
}
