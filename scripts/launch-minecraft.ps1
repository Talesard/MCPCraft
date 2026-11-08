# Launches Minecraft (Fabric 26.3) directly, without the GUI launcher.
# Builds the classpath from the version JSON, extracts natives and starts the client.
# The offline-mode username/UUID below are placeholders: change them for your own
# dev profile, or override with -Username / -Uuid.
[CmdletBinding()]
param(
    [string]$GameDir = '',
    [string]$Version = 'Fabric 26.3',
    [string]$Username = 'Player',
    [string]$Uuid = '00000000000000000000000000000000',
    [string]$JavaExe = '',
    [int]$Width = 1280,
    [int]$Height = 720,
    [string]$Memory = '4G',
    [switch]$Console,
    [switch]$Wait,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if (-not $GameDir) {
    if ($env:MINECRAFT_DIR) { $GameDir = $env:MINECRAFT_DIR }
    else { $GameDir = Join-Path $env:APPDATA '.minecraft' }
}

if (-not $JavaExe) {
    # Prefer JAVA_HOME, then the runtime that the launcher downloaded.
    $exeNames = if ($Console) { @('java.exe', 'javaw.exe') } else { @('javaw.exe', 'java.exe') }
    $jreCandidates = @()
    if ($env:JAVA_HOME) { $jreCandidates += $env:JAVA_HOME }
    $jreCandidates += @(
        (Join-Path $GameDir 'runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon')
    )
    foreach ($jre in $jreCandidates) {
        foreach ($name in $exeNames) {
            $candidate = Join-Path $jre "bin\$name"
            if (Test-Path $candidate) { $JavaExe = $candidate; break }
        }
        if ($JavaExe) { break }
    }
}
if (-not $JavaExe -or -not (Test-Path $JavaExe)) {
    throw "Java not found. Pass -JavaExe or set JAVA_HOME."
}

$versionDir = Join-Path $GameDir "versions\$Version"
$jsonPath = Join-Path $versionDir "$Version.json"
if (-not (Test-Path $jsonPath)) { throw "Version JSON not found: $jsonPath" }
$json = Get-Content $jsonPath -Raw | ConvertFrom-Json

$clientJar = Join-Path $versionDir "$Version.jar"
if (-not (Test-Path $clientJar)) {
    $clientJar = Join-Path $GameDir "versions\$($json.jar)\$($json.jar).jar"
}

$librariesRoot = Join-Path $GameDir 'libraries'
$nativesDir = Join-Path $versionDir 'natives'

# OS rules: allow/deny for windows.
function Test-Allowed($lib) {
    if (-not $lib.rules) { return $true }
    $allowed = $false
    foreach ($rule in $lib.rules) {
        $osName = $null
        if ($rule.os -and $rule.os.name) { $osName = $rule.os.name }
        $matchesOs = (-not $osName) -or ($osName -eq 'windows')
        if ($matchesOs) { $allowed = ($rule.action -eq 'allow') }
    }
    return $allowed
}

# Maven layout path (group:artifact:version[:classifier]).
function Get-MavenRelativePath([string]$name) {
    $parts = $name.Split(':')
    $group = $parts[0]
    $artifact = $parts[1]
    $version = $parts[2]
    $classifier = if ($parts.Count -gt 3) { $parts[3] } else { $null }
    $fileName = if ($classifier) { "$artifact-$version-$classifier.jar" } else { "$artifact-$version.jar" }
    return (($group -replace '\.', '\') + "\$artifact\$version\$fileName")
}

$cp = New-Object System.Collections.Generic.List[string]
$extractList = New-Object System.Collections.Generic.List[string]

foreach ($lib in $json.libraries) {
    if (-not (Test-Allowed $lib)) { continue }
    if ($lib.name -eq 'ru.legacylauncher:fabric-loader-l10n:1.0') { continue }

    $rel = Get-MavenRelativePath $lib.name
    $full = Join-Path $librariesRoot $rel
    if (-not (Test-Path $full)) {
        Write-Verbose "Skipped library: $($lib.name)"
        continue
    }

    $isNative = $lib.name -match ':natives-'
    if ($isNative) {
        # arm64 variants are irrelevant on x64
        if ($lib.name -match 'arm64') { continue }
        $extractList.Add($full)
        continue
    }
    $cp.Add($full)
}

$cp.Add($clientJar)
$classpath = ($cp -join ';')

# Extract Windows natives into the natives directory, as the launcher does.
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in $extractList) {
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            # LWJGL 3.4 natives live under windows/x64/... — strip the prefix so DLLs sit together.
            $rel = $entry.FullName -replace '^(windows/x64/|META-INF/windows/x64/)', ''
            $target = Join-Path $nativesDir $rel
            $dir = Split-Path $target -Parent
            if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
        $zip.Dispose()
    } catch {
        Write-Warning "Could not extract natives from $jar`: $($_.Exception.Message)"
    }
}

# java.library.path is not recursive: gather every DLL into the natives root.
foreach ($src in @(
        $nativesDir,
        (Join-Path $nativesDir 'windows\x64\org\lwjgl'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\freetype'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\jemalloc'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\openal'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\opengl'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\sdl'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\shaderc'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\spvc'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\stb'),
        (Join-Path $nativesDir 'windows\x64\org\lwjgl\vma'),
        (Join-Path $nativesDir 'lwjgl\3.4.3-snapshot\x64'))) {
    if (-not (Test-Path $src)) { continue }
    Get-ChildItem $src -Filter '*.dll' -File -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.DirectoryName -ne $nativesDir) {
            Copy-Item $_.FullName (Join-Path $nativesDir $_.Name) -Force
        }
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $nativesDir 'jna') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $nativesDir 'lwjgl') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $nativesDir 'netty') | Out-Null

$jvmArgs = @(
    "-Xmx$Memory",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseG1GC",
    "-XX:G1NewSizePercent=20",
    "-XX:G1ReservePercent=20",
    "-XX:MaxGCPauseMillis=50",
    "-XX:G1HeapRegionSize=32M",
    "-DFabricMcEmu= net.minecraft.client.main.Main ",
    "-Djava.library.path=$nativesDir",
    "-Djna.tmpdir=$($nativesDir)\jna",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$($nativesDir)\lwjgl",
    "-Dio.netty.native.workdir=$($nativesDir)\netty",
    "-Dminecraft.launcher.brand=custom",
    "-Dminecraft.launcher.version=169",
    "-cp", $classpath,
    "net.fabricmc.loader.impl.launch.knot.KnotClient"
)

$gameArgs = @(
    "--username", $Username,
    "--version", $Version,
    "--gameDir", $GameDir,
    "--assetsDir", (Join-Path $GameDir 'assets'),
    "--assetIndex", $json.assetIndex.id,
    "--uuid", $Uuid,
    "--accessToken", "0",
    "--versionType", "release",
    "--width", "$Width",
    "--height", "$Height"
)

$all = $jvmArgs + $gameArgs

Write-Host "Java    : $JavaExe"
Write-Host "Version : $Version"
Write-Host "Libs    : $($cp.Count) on classpath, $($extractList.Count) native archives"
Write-Host "Natives : $nativesDir"

if ($DryRun) { return }

$proc = Start-Process -FilePath $JavaExe -ArgumentList $all -WorkingDirectory $GameDir -PassThru
Write-Host "Minecraft started: PID $($proc.Id)" -ForegroundColor Green

if ($Wait) {
    $proc.WaitForExit()
    Write-Host "Minecraft exited with code $($proc.ExitCode)"
}
