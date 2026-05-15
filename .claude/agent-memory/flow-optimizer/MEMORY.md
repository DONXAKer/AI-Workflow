# Memory Index

- [CLI route is tool-less single-shot](project_cli_route_toolless.md) — LlmClient.completeWithToolsViaCli ignores tool defs/executor/iterations; agent_verify et al degrade silently on CLAUDE_CODE_CLI route
- [WarCard review_bp MAX_ITERATIONS](project_warcard_review_bp_mxiter.md) — review_bp орчестратора стабильно упирается в MAX_ITERATIONS с пустым content из-за нерелевантного acceptance_checklist для BP-ветки
- [Cache key gaps](project_cache_key_gaps.md) — BlockCacheService.computeKey не хеширует IntegrationConfig.extraConfigJson и Project.defaultProvider; устаревшие хиты после смены настроек
- [Escalation default cross-provider](project_escalation_default_cross_provider.md) — global default escalation ladder идёт в OpenRouter независимо от Project.defaultProvider; AITUNNEL/OLLAMA проекты получают cross-provider сюрприз
- [Bash approval global auto-approve](project_bash_approval_global_autoapprove.md) — BashApprovalGate.autoApproveBlock без TTL/command-pattern check; один «Allow all» открывает весь блок до DenyList
