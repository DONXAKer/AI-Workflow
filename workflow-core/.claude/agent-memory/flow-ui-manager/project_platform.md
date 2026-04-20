---
name: Platform overview
description: Workflow platform purpose, backend stack, block registry, and run lifecycle
type: project
---

AI-driven development pipeline platform. Full cycle: Requirement (or YouTrack issue) → Analysis → Clarification → YouTrack tasks → Code generation → GitLab/GitHub MR → CI monitoring.

**Backend:** Spring Boot 3.4 / Java 21, Spring WebSocket (STOMP over SockJS), Spring Data JPA, H2 database, WebFlux for async HTTP. No Lombok — plain POJOs/records.

**Frontend repo:** `/Users/home/Code/WorkflowJava/workflow-ui`
**Backend repo:** `/Users/home/Code/WorkflowJava/workflow-core`

## Block registry

| block name | class |
|---|---|
| `analysis` | AnalysisBlock |
| `clarification` | ClarificationBlock |
| `youtrack_tasks` | YouTrackTaskCreationBlock |
| `youtrack_input` | YouTrackInputBlock |
| `youtrack_tasks_input` | YouTrackTasksInputBlock |
| `code_generation` | CodeGenerationBlock |
| `git_branch_input` | GitBranchInputBlock |
| `gitlab_mr` | GitLabMRBlock |
| `github_pr` | GitHubPRBlock |
| `mr_input` | MrInputBlock |
| `gitlab_ci` | GitLabCIBlock |
| `github_actions` | GitHubActionsBlock |

## Run lifecycle / statuses (backend `RunStatus.java`)
PENDING → RUNNING → PAUSED_FOR_APPROVAL → COMPLETED / FAILED

Human-in-the-loop approval gate is on each block by default; can be disabled per block (`autoApprove` list on `PipelineRun`).

**Why:** New product for dev teams; v1 audience is developers using YAML/DSL config. v2+ will have a visual constructor.
**How to apply:** When designing UI, keep developer-facing detail (raw JSON output, block IDs, config paths) prominent — this is not a consumer app.
