# Builds the mod. Looks for JDK 25 and Gradle >= 9.8 (Loom 1.19 requires it).
# Override the search with JAVA_HOME / GRADLE_HOME or the -JavaHome / -Gradle arguments.
[CmdletBinding()]
param(
    [switch]$Offline,
    [string]$Task = 'build',
    [string]$JavaHome = '',
    [string]$Gradle = '',
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Find-Jdk25 {
    $candidates = @()

    if ($JavaHome) { $candidates += (Join-Path $JavaHome 'bin\javac.exe') }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\javac.exe') }

    # The Minecraft launcher ships its own JRE; the folder name differs per launcher.
    foreach ($launcherJre in @(
            "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon",
            "$env:APPDATA\.tlauncher\legacy\Minecraft\jre\java-runtime-epsilon\windows-x64\java-runtime-epsilon")) {
        $candidates += (Join-Path $launcherJre 'bin\javac.exe')
    }

    foreach ($base in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
                        "$env:ProgramFiles\Microsoft\jdk", "$env:USERPROFILE\.jdks")) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '25' } |
                ForEach-Object { $candidates += (Join-Path $_.FullName 'bin\javac.exe') }
        }
    }

    if (Get-Command javac -ErrorAction SilentlyContinue) {
        $candidates += (Get-Command javac).Source
    }

    foreach ($javac in $candidates) {
        if (-not $javac -or -not (Test-Path $javac)) { continue }
        $out = & $javac -version 2>&1 | Out-String
        if ($out -match 'javac\s+25') {
            return (Split-Path (Split-Path $javac -Parent) -Parent)
        }
    }
    return $null
}

function Find-Gradle {
    if ($Gradle) { return $Gradle }
    if ($env:GRADLE_HOME -and (Test-Path (Join-Path $env:GRADLE_HOME 'bin\gradle.bat'))) {
        return (Join-Path $env:GRADLE_HOME 'bin\gradle.bat')
    }

    # Any Gradle >= 9.8 already unpacked in the wrapper cache.
    $dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
    if (Test-Path $dists) {
        $found = Get-ChildItem $dists -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match 'gradle-9\.(9|1[0-9])' } |
            Select-Object -First 1 -ExpandProperty FullName
        if ($found) { return $found }
    }

    if (Get-Command gradle -ErrorAction SilentlyContinue) { return 'gradle' }
    return $null
}

$jdk = Find-Jdk25
if (-not $jdk) {
    throw 'JDK 25 not found. Install JDK 25, pass -JavaHome, or run the Minecraft launcher once so its JRE is downloaded.'
}
$gradleExe = Find-Gradle
if (-not $gradleExe) {
    throw 'Gradle >= 9.8 not found. Unpack gradle-9.9.0-bin.zip, set GRADLE_HOME or pass -Gradle.'
}

Write-Host "JDK 25 : $jdk" -ForegroundColor Green
Write-Host "Gradle : $gradleExe" -ForegroundColor Green

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$gradleArgs = @($Task, '--console=plain', '--project-dir', $root,
    "-Dorg.gradle.java.installations.paths=$jdk")
if ($Offline) { $gradleArgs += '--offline' }
$gradleArgs += $ExtraArgs

& $gradleExe @gradleArgs
exit $LASTEXITCODE
