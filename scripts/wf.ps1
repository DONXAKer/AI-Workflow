# AI-Workflow CLI client
#
# Usage:
#   .\scripts\wf.ps1 runs                          # list active + recent runs
#   .\scripts\wf.ps1 status <runId>                # detailed status of a run
#   .\scripts\wf.ps1 approve <runId> [comment]     # approve a paused step
#   .\scripts\wf.ps1 cancel <runId>                # cancel a running run
#   .\scripts\wf.ps1 logs <runId>                  # show block outputs / log
#   .\scripts\wf.ps1 run <configPath> <taskFile>   # start a new run
#
# Optional env vars:
#   $env:WF_HOST      = "http://localhost:8020"    (default)
#   $env:WF_USER      = "admin"                    (default)
#   $env:WF_PASSWORD  = "<password>"               (required for write operations)

$ErrorActionPreference = "Stop"

$BASE  = if ($env:WF_HOST)     { $env:WF_HOST }     else { "http://localhost:8020" }
$USER  = if ($env:WF_USER)     { $env:WF_USER }     else { "admin" }
$PASS  = if ($env:WF_PASSWORD) { $env:WF_PASSWORD } else { "" }

$script:Session = $null
$script:Csrf    = $null

# ── helpers ──────────────────────────────────────────────────────────────────

function Get-Session {
    if ($script:Session) { return }
    if (-not $PASS) { Write-Error "Set `$env:WF_PASSWORD before running write commands." }

    $script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

    # 1. GET any page to get XSRF-TOKEN cookie
    $r = Invoke-WebRequest "$BASE/actuator/health" -SessionVariable sv -ErrorAction SilentlyContinue
    $script:Session = $sv

    # 2. Login
    $body = @{ username = $USER; password = $PASS } | ConvertTo-Json
    $xsrf = ($script:Session.Cookies.GetCookies($BASE) | Where-Object Name -eq "XSRF-TOKEN").Value
    Invoke-RestMethod "$BASE/api/auth/login" `
        -Method POST -Body $body -ContentType "application/json" `
        -WebSession $script:Session | Out-Null

    # 3. Refresh CSRF token after login
    Invoke-WebRequest "$BASE/actuator/health" -WebSession $script:Session -ErrorAction SilentlyContinue | Out-Null
    $script:Csrf = ($script:Session.Cookies.GetCookies($BASE) | Where-Object Name -eq "XSRF-TOKEN").Value
}

function Invoke-GET($path) {
    Invoke-RestMethod "$BASE$path" -Method GET
}

function Invoke-POST($path, $body) {
    Get-Session
    $json = if ($body) { $body | ConvertTo-Json -Depth 10 } else { "{}" }
    Invoke-RestMethod "$BASE$path" `
        -Method POST -Body $json -ContentType "application/json" `
        -Headers @{ "X-XSRF-TOKEN" = $script:Csrf } `
        -WebSession $script:Session
}

function Status-Color($s) {
    switch ($s) {
        "RUNNING"              { "`e[33m$s`e[0m" }  # yellow
        "COMPLETED"            { "`e[32m$s`e[0m" }  # green
        "FAILED"               { "`e[31m$s`e[0m" }  # red
        "PAUSED_FOR_APPROVAL"  { "`e[36m$s`e[0m" }  # cyan
        "CANCELLED"            { "`e[90m$s`e[0m" }  # grey
        default                { $s }
    }
}

function Format-Ago($iso) {
    if (-not $iso) { return "-" }
    $d = [datetime]::Parse($iso)
    $ago = [datetime]::UtcNow - $d.ToUniversalTime()
    if ($ago.TotalMinutes -lt 1)  { return "$([int]$ago.TotalSeconds)s ago" }
    if ($ago.TotalHours   -lt 1)  { return "$([int]$ago.TotalMinutes)m ago" }
    return "$([int]$ago.TotalHours)h ago"
}

# ── commands ──────────────────────────────────────────────────────────────────

function cmd-runs {
    $data = Invoke-GET "/api/runs?page=0&size=20"
    $items = if ($data.content) { $data.content } else { $data }

    if (-not $items) { Write-Host "No runs found."; return }

    Write-Host ""
    Write-Host ("  {0,-36}  {1,-22}  {2,-8}  {3}" -f "RUN ID", "STATUS", "STARTED", "REQUIREMENT")
    Write-Host ("  " + "-"*90)
    foreach ($r in $items) {
        $sid   = $r.id.ToString().Substring(0, 8) + "…"
        $stat  = Status-Color $r.status
        $ago   = Format-Ago $r.startedAt
        $req   = if ($r.requirement.Length -gt 50) { $r.requirement.Substring(0,50) + "…" } else { $r.requirement }
        Write-Host ("  {0,-36}  {1,-30}  {2,-8}  {3}" -f $r.id, $stat, $ago, $req)
    }
    Write-Host ""
}

function cmd-status($runId) {
    $r = Invoke-GET "/api/runs/$runId"

    Write-Host ""
    Write-Host "  Run:        $($r.id)"
    Write-Host "  Status:     $(Status-Color $r.status)"
    Write-Host "  Config:     $($r.configPath)"
    Write-Host "  Requirement: $($r.requirement)"
    Write-Host "  Started:    $($r.startedAt)  ($(Format-Ago $r.startedAt))"
    if ($r.finishedAt) {
        Write-Host "  Finished:   $($r.finishedAt)"
    }
    if ($r.currentBlockId) {
        Write-Host "  Current:    $($r.currentBlockId)"
    }

    if ($r.completedBlocks) {
        Write-Host ""
        Write-Host "  Blocks:"
        foreach ($b in $r.completedBlocks) {
            $icon = if ($b.success -eq $false) { "✗" } else { "✓" }
            Write-Host "    $icon $($b.blockId)"
        }
    }

    if ($r.status -eq "PAUSED_FOR_APPROVAL") {
        Write-Host ""
        Write-Host "  `e[36m⏸  Waiting for approval on block: $($r.currentBlockId)`e[0m"
        Write-Host "  Run: .\scripts\wf.ps1 approve $($r.id)"
    }
    Write-Host ""
}

function cmd-approve($runId, $comment) {
    if (-not $runId) { Write-Error "Usage: wf approve <runId> [comment]" }
    $body = @{ action = "approve" }
    if ($comment) { $body.comment = $comment }
    Invoke-POST "/api/runs/$runId/approval" $body | Out-Null
    Write-Host "  `e[32m✓ Approved run $runId`e[0m"
}

function cmd-cancel($runId) {
    if (-not $runId) { Write-Error "Usage: wf cancel <runId>" }
    Invoke-POST "/api/runs/$runId/cancel" $null | Out-Null
    Write-Host "  `e[33m✗ Cancelled run $runId`e[0m"
}

function cmd-logs($runId) {
    if (-not $runId) { Write-Error "Usage: wf logs <runId>" }
    $r = Invoke-GET "/api/runs/$runId"

    Write-Host ""
    Write-Host "  Logs for run $runId"
    Write-Host ("  " + "-"*60)

    if (-not $r.completedBlocks) { Write-Host "  No block outputs yet."; return }

    foreach ($b in $r.completedBlocks) {
        Write-Host ""
        Write-Host "  `e[1m[$($b.blockId)]`e[0m"
        if ($b.outputJson) {
            try {
                $obj = $b.outputJson | ConvertFrom-Json
                $obj.PSObject.Properties | ForEach-Object {
                    $val = $_.Value
                    if ($val -is [string] -and $val.Length -gt 200) {
                        $val = $val.Substring(0, 200) + "…"
                    }
                    Write-Host "    $($_.Name): $val"
                }
            } catch {
                Write-Host "    $($b.outputJson)"
            }
        }
    }
    Write-Host ""
}

function cmd-run($configPath, $taskFile) {
    if (-not $configPath -or -not $taskFile) {
        Write-Error "Usage: wf run <configPath> <taskFile>"
    }
    $body = @{
        configPath   = $configPath
        entryPointId = "implement"
        requirement  = $taskFile
    }
    $r = Invoke-POST "/api/runs" $body
    Write-Host ""
    Write-Host "  `e[32m✓ Run started`e[0m"
    Write-Host "  ID:     $($r.runId)"
    Write-Host "  Status: $($r.status)"
    Write-Host ""
    Write-Host "  Follow with:"
    Write-Host "    .\scripts\wf.ps1 status $($r.runId)"
    Write-Host ""
}

function Show-Help {
    Write-Host @"

  AI-Workflow CLI  (wf.ps1)

  COMMANDS
    runs                          List active and recent pipeline runs
    status  <runId>               Show detailed status of a run
    approve <runId> [comment]     Approve a paused step
    cancel  <runId>               Cancel a running run
    logs    <runId>               Show block outputs
    run     <configPath> <task>   Start a new run

  ENV VARS
    WF_HOST      API base URL  (default: http://localhost:8020)
    WF_USER      Username      (default: admin)
    WF_PASSWORD  Password      (required for approve / cancel / run)

  EXAMPLES
    `$env:WF_PASSWORD="secret"
    .\scripts\wf.ps1 runs
    .\scripts\wf.ps1 run /warcard/.ai-workflow/pipelines/feature.yaml /warcard/tasks/active/MCP-CONTENT-004_match-arena-recipe.md
    .\scripts\wf.ps1 status 0776dde0-6e9a-4783-a3d9-1d57b2aa161b
    .\scripts\wf.ps1 approve 0776dde0-6e9a-4783-a3d9-1d57b2aa161b "looks good"
    .\scripts\wf.ps1 cancel  0776dde0-6e9a-4783-a3d9-1d57b2aa161b

"@
}

# ── dispatch ──────────────────────────────────────────────────────────────────

$cmd = $args[0]
switch ($cmd) {
    "runs"    { cmd-runs }
    "status"  { cmd-status  $args[1] }
    "approve" { cmd-approve $args[1] $args[2] }
    "cancel"  { cmd-cancel  $args[1] }
    "logs"    { cmd-logs    $args[1] }
    "run"     { cmd-run     $args[1] $args[2] }
    default   { Show-Help }
}
