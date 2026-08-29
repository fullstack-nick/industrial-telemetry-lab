[CmdletBinding()]
param(
    [string] $EnvFile,
    [switch] $Force
)

. (Join-Path $PSScriptRoot "lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    $project = Get-TelemetryEnvValue "COMPOSE_PROJECT_NAME" "industrial-telemetry-lab"
    $volumes = @(& docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}')
    Write-Host "The following project-scoped Docker volumes will be permanently removed:" -ForegroundColor Yellow
    if ($volumes.Count -eq 0) { Write-Host "  (none currently exist)" } else { $volumes | ForEach-Object { Write-Host "  $_" } }
    if (-not $Force) {
        $answer = Read-Host "Type RESET to continue"
        if ($answer -cne "RESET") { Write-Host "Reset cancelled."; return }
    }
    Invoke-TelemetryCompose @("down", "--volumes", "--remove-orphans")
    Write-Host "Removed only containers, networks, and named volumes owned by Compose project '$project'. This data is not recoverable." -ForegroundColor Yellow
} finally {
    Pop-Location
}
