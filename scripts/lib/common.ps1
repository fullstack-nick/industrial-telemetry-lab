Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:TelemetryRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$script:TelemetryEnvFile = $null

function Use-TelemetryEnvironment {
    param([string] $Path)

    if (-not $Path) {
        $candidate = Join-Path $script:TelemetryRepoRoot ".env"
        if (-not (Test-Path $candidate)) {
            $candidate = Join-Path $script:TelemetryRepoRoot ".env.example"
            Write-Host "INFO: .env is absent; using the synthetic .env.example values." -ForegroundColor Cyan
        }
        $Path = $candidate
    }
    $script:TelemetryEnvFile = (Resolve-Path $Path).Path
}

function Get-TelemetryEnvValue {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [string] $Default = ""
    )

    $line = Get-Content $script:TelemetryEnvFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -Last 1
    if (-not $line) { return $Default }
    return ($line -split "=", 2)[1]
}

function Invoke-TelemetryCompose {
    param([Parameter(Mandatory, Position = 0)] [string[]] $Arguments)

    & docker compose --env-file $script:TelemetryEnvFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Get-DatabaseScalar {
    param([Parameter(Mandatory)] [string] $Sql)

    $user = Get-TelemetryEnvValue "POSTGRES_USER" "telemetry"
    $database = Get-TelemetryEnvValue "POSTGRES_DB" "telemetry"
    $output = & docker compose --env-file $script:TelemetryEnvFile exec -T timescaledb psql -U $user -d $database -At -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "Database assertion query failed." }
    return ($output | Out-String).Trim()
}

function Get-QueueReadyCount {
    param([string] $Queue = "telemetry.main")

    $lines = & docker compose --env-file $script:TelemetryEnvFile exec -T rabbitmq rabbitmqctl list_queues --silent name messages_ready
    if ($LASTEXITCODE -ne 0) { throw "RabbitMQ queue inspection failed." }
    foreach ($line in $lines) {
        if ($line -match "^$([regex]::Escape($Queue))\s+(\d+)$") {
            return [long] $Matches[1]
        }
    }
    throw "Queue '$Queue' was not declared."
}

function Wait-ForCondition {
    param(
        [Parameter(Mandatory)] [scriptblock] $Condition,
        [Parameter(Mandatory)] [string] $Description,
        [int] $TimeoutSeconds = 90,
        [int] $PollSeconds = 2
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            if (& $Condition) {
                Write-Host "ASSERT PASS: $Description" -ForegroundColor Green
                return
            }
        } catch {
            # Transient connection errors are expected while a service is recovering.
        }
        Start-Sleep -Seconds $PollSeconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Timed out after ${TimeoutSeconds}s: $Description"
}

function Assert-Condition {
    param(
        [Parameter(Mandatory)] [bool] $Condition,
        [Parameter(Mandatory)] [string] $Description
    )

    if (-not $Condition) { throw "ASSERT FAIL: $Description" }
    Write-Host "ASSERT PASS: $Description" -ForegroundColor Green
}

function Wait-TelemetryHttp {
    param(
        [Parameter(Mandatory)] [string] $Url,
        [int] $TimeoutSeconds = 90
    )

    Wait-ForCondition -Description "$Url is reachable" -TimeoutSeconds $TimeoutSeconds -Condition {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    }
}

function Wait-CoreStack {
    Wait-TelemetryHttp "http://localhost:8081/actuator/health/readiness"
    Wait-TelemetryHttp "http://localhost:8080/actuator/health/readiness"
    Wait-TelemetryHttp "http://localhost:8083/actuator/health/readiness"
    Wait-TelemetryHttp "http://localhost:8082/actuator/health/readiness"
}

function Start-CoreStack {
    Invoke-TelemetryCompose @("up", "-d")
    Wait-CoreStack
}

function Get-CollectorStatus {
    return Invoke-RestMethod -Uri "http://localhost:8082/collector/v1/status" -TimeoutSec 5
}

function Set-SimulatorFaults {
    param([Parameter(Mandatory)] [hashtable] $Faults)

    $token = Get-TelemetryEnvValue "LOCAL_API_TOKEN" "local-development-token-change-me"
    $headers = @{ Authorization = "Bearer $token" }
    return Invoke-RestMethod -Method Put -Uri "http://localhost:8081/controller/v1/faults" -Headers $headers -ContentType "application/json" -Body ($Faults | ConvertTo-Json -Compress) -TimeoutSec 10
}

function Reset-SimulatorFaults {
    $null = Set-SimulatorFaults @{
        duplicateRate = 0
        outOfOrderRate = 0
        invalidUnitRate = 0
        badQualityRate = 0
        futureTimestampRate = 0
        responseDelayMs = 0
        connectionAvailable = $true
        newUnknownTagEnabled = $false
    }
}

function Wait-ForSpoolDrain {
    param([int] $TimeoutSeconds = 120)

    Wait-ForCondition -Description "collector spool and pending batches drain" -TimeoutSeconds $TimeoutSeconds -Condition {
        $status = Get-CollectorStatus
        return [long] $status.spoolObservationCount -eq 0 -and [long] $status.pendingBatchCount -eq 0
    }
}

function Write-ScenarioHeader {
    param([Parameter(Mandatory)] [string] $Name)

    Write-Host ""
    Write-Host "SCENARIO: $Name" -ForegroundColor Cyan
    Write-Host ("=" * (10 + $Name.Length))
}

function Write-ScenarioPass {
    param([Parameter(Mandatory)] [string] $Name)

    Write-Host "Result: PASS ($Name)" -ForegroundColor Green
}

Use-TelemetryEnvironment
