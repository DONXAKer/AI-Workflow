package com.workflow.llm;

public record LlmCostSummary(String model, long calls, long tokensIn, long tokensOut, double costUsd) {}
