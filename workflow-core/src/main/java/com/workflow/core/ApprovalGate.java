package com.workflow.core;

import java.util.List;
import java.util.Map;

public interface ApprovalGate {
    ApprovalResult request(String blockId, String blockType, String description,
                           Map<String, Object> inputData, Map<String, Object> outputData,
                           List<String> remainingBlockIds);
}
