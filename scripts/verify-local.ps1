[CmdletBinding()]
param(
    [string] $EnvFile,
    [switch] $SkipScenarios
)

. (Join-Path $PSScriptRoot "lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    & (Join-Path $PSScriptRoot "check-prerequisites.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Prerequisite checks failed." }

    Write-Host "Running the Maven unit, formatting, SpotBugs, and coverage gates..." -ForegroundColor Cyan
    & (Join-Path $script:TelemetryRepoRoot "mvnw.cmd") --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed." }

    & docker compose --env-file $script:TelemetryEnvFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Core Compose configuration is invalid." }
    & docker compose --env-file $script:TelemetryEnvFile --profile observability config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Observability Compose configuration is invalid." }
    $composeModel = (& docker compose --env-file $script:TelemetryEnvFile --profile observability config --format json | Out-String) | ConvertFrom-Json
    foreach ($service in $composeModel.services.PSObject.Properties.Value) {
        $portsProperty = $service.PSObject.Properties["ports"]
        if ($null -ne $portsProperty) {
            foreach ($port in @($portsProperty.Value)) {
                if ($null -ne $port.published) {
                    Assert-Condition ($port.host_ip -eq "127.0.0.1") "published port $($port.published) binds only to loopback"
                }
            }
        }
        $volumesProperty = $service.PSObject.Properties["volumes"]
        if ($null -ne $volumesProperty) {
            foreach ($volume in @($volumesProperty.Value)) {
                Assert-Condition ($volume.source -ne "/var/run/docker.sock" -and $volume.target -ne "/var/run/docker.sock") "service does not mount the Docker daemon socket"
            }
        }
    }
    Get-ChildItem (Join-Path $script:TelemetryRepoRoot "observability/grafana/dashboards/*.json") | ForEach-Object {
        Get-Content -Raw $_.FullName | ConvertFrom-Json | Out-Null
    }
    Write-Host "ASSERT PASS: Compose and dashboard artifacts parse cleanly" -ForegroundColor Green

    Invoke-TelemetryCompose @("up", "-d", "--build")
    Wait-CoreStack
    $applicationLogs = (& docker compose --env-file $script:TelemetryEnvFile logs --no-color controller-simulator edge-collector telemetry-gateway telemetry-worker | Out-String)
    foreach ($name in @("LOCAL_API_TOKEN", "LOCAL_ADMIN_TOKEN", "POSTGRES_PASSWORD", "RABBITMQ_DEFAULT_PASS", "OBJECT_STORE_ACCESS_KEY", "OBJECT_STORE_SECRET_KEY", "GRAFANA_ADMIN_PASSWORD")) {
        $secret = Get-TelemetryEnvValue $name
        if ($secret) { Assert-Condition (-not $applicationLogs.Contains($secret)) "$name value is absent from application logs" }
    }
    & (Join-Path $PSScriptRoot "verify-openapi.ps1")
    if (-not $SkipScenarios) {
        & (Join-Path $PSScriptRoot "run-end-to-end-tests.ps1") -EnvFile $script:TelemetryEnvFile
    }
    Write-Host "Local verification passed." -ForegroundColor Green
} finally {
    Pop-Location
}
