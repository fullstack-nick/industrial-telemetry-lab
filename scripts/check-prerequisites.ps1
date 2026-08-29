[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Report-Check([string] $Name, [bool] $Passed, [string] $Detail) {
    $label = if ($Passed) { "PASS" } else { "FAIL" }
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host ("{0,-5} {1,-24} {2}" -f $label, $Name, $Detail) -ForegroundColor $color
    if (-not $Passed) { $failures.Add("$Name`: $Detail") }
}

$javaOutput = (& java -version 2>&1 | Out-String)
Report-Check "Java 21" ($LASTEXITCODE -eq 0 -and $javaOutput -match 'version "21\.') ($javaOutput.Split("`n")[0].Trim())

$dockerOutput = (& docker info --format '{{.ServerVersion}}' 2>&1 | Out-String).Trim()
Report-Check "Docker daemon" ($LASTEXITCODE -eq 0) $(if ($LASTEXITCODE -eq 0) { "server $dockerOutput" } else { "Start Docker Desktop." })

$composeOutput = (& docker compose version --short 2>&1 | Out-String).Trim()
Report-Check "Docker Compose v2" ($LASTEXITCODE -eq 0) $(if ($LASTEXITCODE -eq 0) { "version $composeOutput" } else { "Install the Docker Compose v2 plugin." })

$gitOutput = (& git --version 2>&1 | Out-String).Trim()
Report-Check "Git" ($LASTEXITCODE -eq 0) $gitOutput
Report-Check "Maven Wrapper" (Test-Path (Join-Path $repoRoot "mvnw.cmd")) "repository wrapper"

$drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($repoRoot).Substring(0, 1))
$freeGiB = [math]::Round($drive.Free / 1GB, 1)
Report-Check "Free disk" ($freeGiB -ge 15) "$freeGiB GiB free; 15 GiB required"

$envFile = if (Test-Path (Join-Path $repoRoot ".env")) { Join-Path $repoRoot ".env" } else { Join-Path $repoRoot ".env.example" }
$projectPorts = (& docker compose --env-file $envFile ps 2>$null | Out-String)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
foreach ($port in @(3000, 3100, 3200, 5432, 5672, 8080, 8081, 8082, 8083, 8333, 8888, 9090, 9333, 12345, 15672)) {
    $occupied = $listeners.LocalPort -contains $port
    $ownedByProject = $projectPorts -match "127\.0\.0\.1:$port->"
    Report-Check "Port $port" (-not $occupied -or $ownedByProject) $(if (-not $occupied) { "available" } elseif ($ownedByProject) { "used by this Compose project" } else { "occupied by another process" })
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Correct the failed prerequisites above; this script did not modify the host." -ForegroundColor Yellow
    exit 1
}
Write-Host "All prerequisites are ready. No host changes were made." -ForegroundColor Green
