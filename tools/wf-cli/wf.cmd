@echo off
:: Global wf CLI — wraps Docker container.
:: Place this file in a directory on your PATH (e.g. C:\tools\).
::
:: First-time setup:
::   docker build -t wf-cli D:\path\to\AI-Workflow\tools\wf-cli
::   set WF_PASSWORD=your-password   (or add to system env vars)
::
:: Usage:
::   wf runs
::   wf run MCP-CONTENT-004
::   wf status <runId>
::   wf approve <runId>
::   wf cancel  <runId>
::   wf logs    <runId>

docker run --rm ^
  -e WF_HOST=%WF_HOST% ^
  -e WF_USER=%WF_USER% ^
  -e WF_PASSWORD=%WF_PASSWORD% ^
  -e WF_PROJECT_DIR=/project ^
  -v "%WF_PROJECT_DIR%:/project" ^
  wf-cli %*
