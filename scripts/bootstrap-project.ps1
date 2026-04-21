# Creates a Project row on a running AI-Workflow instance.
# Handles: healthcheck -> admin login -> CSRF token -> POST /api/projects.
#
# Usage:
#   $env:WF_ADMIN_PASSWORD = "<bootstrap password from gradle bootRun logs>"
#   .\scripts\bootstrap-project.ps1
#
# Optional overrides:
#   $env:WF_HOST         = "http://localhost:8020"   # default
#   $env:WF_PROJECT_SLUG = "myproject"               # default
#   $env:WF_PROJECT_WD   = "/path/to/repo"           # default (required)

$ErrorActionPreference = "Stop"

$host_  = if ($env:WF_HOST)         { $env:WF_HOST }         else { "http://localhost:8020" }
$slug   = if ($env:WF_PROJECT_SLUG) { $env:WF_PROJECT_SLUG } else { "myproject" }
$wd     = if ($env:WF_PROJECT_WD)   { $env:WF_PROJECT_WD }   else { "" }
$pw     = $env:WF_ADMIN_PASSWORD

if (-not $pw) {
    Write-Error "WF_ADMIN_PASSWORD not set. Grep the bootRun logs for 'Bootstrap admin created'."
    exit 1
}
if (-not $wd) {
    Write-Error "WF_PROJECT_WD not set. Example: `$env:WF_PROJECT_WD = '/path/to/repo'"
    exit 1
}

# --- 1. Healthcheck (permitAll)
try {
    $health = Invoke-RestMethod -Uri "$host_/actuator/health" -TimeoutSec 3
    if ($health.status -ne "UP") {
        Write-Error "health != UP: $($health | ConvertTo-Json -Compress)"; exit 1
    }
    Write-Host "[health] UP" -ForegroundColor Green
} catch {
    Write-Error "server not reachable at $host_ — is 'gradle bootRun' running?"
    exit 1
}

# --- 2. Login (CSRF-exempt, sets JSESSIONID + XSRF-TOKEN cookies on $session)
$loginBody = @{ username = "admin"; password = $pw } | ConvertTo-Json -Compress
$session = $null
$login = Invoke-WebRequest -Uri "$host_/api/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body $loginBody `
    -SessionVariable session
if ($login.StatusCode -ne 200) {
    Write-Error "login failed: $($login.StatusCode) $($login.Content)"; exit 1
}
Write-Host "[auth]  logged in as admin" -ForegroundColor Green

# --- 3. Pull XSRF-TOKEN cookie
$csrf = ($session.Cookies.GetCookies($host_) | Where-Object { $_.Name -eq "XSRF-TOKEN" }).Value
if (-not $csrf) {
    Write-Error "no XSRF-TOKEN cookie after login — CSRF config may have changed"; exit 1
}

# --- 4. Check if the project already exists
try {
    $existing = Invoke-RestMethod -Uri "$host_/api/projects/$slug" `
        -Method Get -WebSession $session -ErrorAction Stop
    Write-Host "[done]  project '$slug' already exists (id=$($existing.id))" -ForegroundColor Yellow
    exit 0
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
}

# --- 5. POST /api/projects
$body = @{
    slug        = $slug
    displayName = $slug
    workingDir  = $wd
    description = "Auto-created by bootstrap-project.ps1"
} | ConvertTo-Json -Compress

$created = Invoke-RestMethod -Uri "$host_/api/projects" `
    -Method Post `
    -ContentType "application/json" `
    -Headers @{ "X-XSRF-TOKEN" = $csrf } `
    -Body $body `
    -WebSession $session

Write-Host "[done]  created project:" -ForegroundColor Green
$created | ConvertTo-Json
