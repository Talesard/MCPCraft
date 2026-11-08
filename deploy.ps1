# Deploys the mod into the installed Minecraft build.
# Copies the built jar and Fabric API into the mods directory and prints the result.
# Pass -GameDir, or set the MINECRAFT_DIR environment variable, when the launcher
# keeps the game somewhere else than the default .minecraft folder.
[CmdletBinding()]
param(
    [string]$GameDir = '',
    [switch]$SkipFabricApi
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $GameDir) {
    if ($env:MINECRAFT_DIR) { $GameDir = $env:MINECRAFT_DIR }
    else { $GameDir = Join-Path $env:APPDATA '.minecraft' }
}

$apiVersion = '0.165.2+26.3'
$apiFile = "fabric-api-$apiVersion.jar"

$modsDir = Join-Path $GameDir 'mods'
if (-not (Test-Path $GameDir)) {
    throw "Game directory not found: $GameDir — pass -GameDir or set MINECRAFT_DIR."
}
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null

# 1. The mod itself
$built = Get-ChildItem (Join-Path $root 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $built) {
    throw 'Build the mod first: .\build.ps1'
}

# Remove stale jars from earlier builds so only one version is loaded.
Get-ChildItem $modsDir -Filter 'minecraft-mcp*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $built.Name } |
    ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "Removed stale jar: $($_.Name)" -ForegroundColor Yellow }

Copy-Item $built.FullName (Join-Path $modsDir $built.Name) -Force
Write-Host "Mod installed: $($built.Name)" -ForegroundColor Green

# 2. Fabric API (the mod needs it for lifecycle events)
if (-not $SkipFabricApi) {
    $apiPath = Join-Path $modsDir $apiFile
    $anyApi = Get-ChildItem $modsDir -Filter 'fabric-api-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '26\.3' } | Select-Object -First 1

    if (Test-Path $apiPath) {
        Write-Host "Fabric API already present: $apiFile" -ForegroundColor Green
    } elseif ($anyApi) {
        Write-Host "Another Fabric API build for 26.3 found: $($anyApi.Name)" -ForegroundColor Yellow
    } else {
        Write-Host "Fabric API $apiVersion is missing — downloading from Modrinth..." -ForegroundColor Yellow
        try {
            $meta = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D&loaders=%5B%22fabric%22%5D" -TimeoutSec 60
            $version = $meta | Where-Object { $_.version_number -eq $apiVersion } | Select-Object -First 1
            if (-not $version) { $version = $meta | Select-Object -First 1 }
            $file = $version.files | Where-Object { $_.primary } | Select-Object -First 1
            if (-not $file) { $file = $version.files[0] }
            Invoke-WebRequest -Uri $file.url -OutFile $apiPath -UseBasicParsing -TimeoutSec 300
            Write-Host "Fabric API downloaded: $apiFile" -ForegroundColor Green
        } catch {
            Write-Host "Could not download Fabric API automatically: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host "Download it manually from https://modrinth.com/mod/fabric-api/versions?g=26.3 and place it in $modsDir" -ForegroundColor Yellow
        }
    }
}

Write-Output ""
Write-Output "=== mods directory ==="
Get-ChildItem $modsDir -Filter '*.jar' | Select-Object Name, @{N='MB';E={[math]::Round($_.Length/1MB,2)}} | Format-Table -AutoSize

Write-Host "Done. Restart Minecraft (Fabric 26.3) and check http://127.0.0.1:8765/health" -ForegroundColor Cyan
