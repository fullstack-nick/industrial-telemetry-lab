[CmdletBinding()]
param(
    [string] $EnvFile,
    [switch] $SkipVulnerabilityScan,
    [switch] $SkipImageScan
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedEnv = if ($EnvFile) { (Resolve-Path $EnvFile).Path } elseif (Test-Path (Join-Path $repoRoot ".env")) { Join-Path $repoRoot ".env" } else { Join-Path $repoRoot ".env.example" }
$reportDirectory = Join-Path $repoRoot "target/security"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

Push-Location $repoRoot
try {
    Write-Host "Generating the aggregate CycloneDX SBOM..." -ForegroundColor Cyan
    & (Join-Path $repoRoot "mvnw.cmd") --no-transfer-progress -Psbom -DskipTests verify
    if ($LASTEXITCODE -ne 0) { throw "SBOM generation failed." }
    $bom = Join-Path $repoRoot "target/bom.json"
    if (-not (Test-Path $bom)) { throw "Expected aggregate SBOM was not created at $bom." }
    Write-Host "ASSERT PASS: aggregate SBOM created at target/bom.json" -ForegroundColor Green

    if ($SkipVulnerabilityScan) {
        Write-Host "SKIP: OWASP Dependency-Check was explicitly disabled." -ForegroundColor Yellow
    } elseif ([string]::IsNullOrWhiteSpace($env:NVD_API_KEY)) {
        Write-Host "SKIP: OWASP Dependency-Check requires NVD_API_KEY in the process environment; the SBOM and image audit will continue." -ForegroundColor Yellow
    } else {
        Write-Host "Running OWASP Dependency-Check (the first vulnerability-data download can be slow)..." -ForegroundColor Cyan
        & (Join-Path $repoRoot "mvnw.cmd") --no-transfer-progress -Psecurity-audit -DskipTests verify
        if ($LASTEXITCODE -ne 0) { throw "Dependency-Check failed or found a dependency at/above the configured CVSS threshold." }
        Write-Host "ASSERT PASS: dependency vulnerability policy passed" -ForegroundColor Green
    }

    Write-Host "Recording configured container tags and locally resolved immutable IDs..." -ForegroundColor Cyan
    $composeImages = @(& docker compose --env-file $resolvedEnv --profile observability config --images)
    $dockerfileImages = @(Get-Content (Join-Path $repoRoot "docker/app.Dockerfile") | ForEach-Object {
        if ($_ -match '^\s*FROM\s+([^\s]+)') { $Matches[1] }
    })
    $images = @($composeImages + $dockerfileImages | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0 -or $images.Count -eq 0) { throw "Could not resolve Compose image references." }
    $inventory = foreach ($reference in $images) {
        $inspection = & docker image inspect $reference --format '{{json .}}' 2>$null
        if ($LASTEXITCODE -eq 0) {
            $image = $inspection | ConvertFrom-Json
            [ordered]@{
                reference = $reference
                imageId = $image.Id
                repositoryDigests = @($image.RepoDigests)
                created = $image.Created
                architecture = $image.Architecture
                operatingSystem = $image.Os
                locallyPresent = $true
            }
        } else {
            [ordered]@{
                reference = $reference
                imageId = $null
                repositoryDigests = @()
                created = $null
                architecture = $null
                operatingSystem = $null
                locallyPresent = $false
            }
        }
    }
    $inventory | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $reportDirectory "container-images.json")
    Write-Host "ASSERT PASS: container inventory created at target/security/container-images.json" -ForegroundColor Green

    if ($SkipImageScan) {
        Write-Host "SKIP: Docker Scout image scan was explicitly disabled." -ForegroundColor Yellow
    } elseif (& docker scout version 2>$null) {
        $scoutDirectory = Join-Path $reportDirectory "docker-scout"
        New-Item -ItemType Directory -Force -Path $scoutDirectory | Out-Null
        $scoutCompleted = 0
        $scoutSkippedForAuthentication = $false
        foreach ($reference in $images) {
            $safeName = ($reference -replace '[^A-Za-z0-9_.-]', '_')
            $scoutOutput = @(& docker scout cves --format sarif --output (Join-Path $scoutDirectory "$safeName.sarif") $reference 2>&1)
            $scoutExitCode = $LASTEXITCODE
            $scoutOutput | ForEach-Object { Write-Host $_ }
            if ($scoutExitCode -ne 0) {
                $renderedOutput = $scoutOutput -join "`n"
                if ($renderedOutput -match '(?i)log in with your Docker ID|docker login|authentication required|unauthorized') {
                    Write-Host "SKIP: Docker Scout requires an authenticated Docker session; container inventory remains available and any partial Scout reports are not a complete audit." -ForegroundColor Yellow
                    $scoutSkippedForAuthentication = $true
                    break
                }
                throw "Docker Scout failed for $reference."
            }
            $scoutCompleted++
        }
        if (-not $scoutSkippedForAuthentication) {
            Write-Host "ASSERT PASS: Docker Scout reports created for $scoutCompleted images under target/security/docker-scout" -ForegroundColor Green
        }
    } else {
        Write-Host "SKIP: Docker Scout is not installed; container IDs were still recorded." -ForegroundColor Yellow
    }

    Write-Host "Local dependency and image audit completed." -ForegroundColor Green
} finally {
    Pop-Location
}
