[CmdletBinding()]
param(
    [string]$Version = "0.1.19",
    [string]$AdminVersion = "0.1.16",
    [string]$JdkHome = "",
    [switch]$SkipTests,
    [switch]$SkipLinux,
    [switch]$PlayerOnly,
    [switch]$AdminOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PlayerOnly -and $AdminOnly) {
    throw "-PlayerOnly and -AdminOnly cannot be used together."
}

$packagingRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $packagingRoot
$distRoot = Join-Path $repoRoot "dist"
$buildRoot = Join-Path $repoRoot "target\packaging"
$templates = Join-Path $packagingRoot "templates"

function Assert-ChildPath([string]$Path, [string]$Parent) {
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $resolvedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if (-not $resolvedPath.StartsWith($resolvedParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside $resolvedParent`: $resolvedPath"
    }
}

function Resolve-Jdk21([string]$Requested) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Requested)) { $candidates += $Requested }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates += $env:JAVA_HOME }
    $candidates += "D:\Program Files\Java\jdk-21"
    $candidates += "C:\Program Files\Java\jdk-21"

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $release = Join-Path $candidate "release"
        $jpackage = Join-Path $candidate "bin\jpackage.exe"
        if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath $jpackage)) {
            $metadata = Get-Content -Raw -LiteralPath $release
            if ($metadata -match 'JAVA_VERSION="21\.') {
                return [System.IO.Path]::GetFullPath($candidate)
            }
        }
    }
    throw "A complete JDK 21 with jpackage is required. Pass -JdkHome <path>."
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE"
    }
}

function Copy-DirectoryContents([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -Force -LiteralPath $Source | ForEach-Object {
        Copy-Item -Recurse -Force -LiteralPath $_.FullName -Destination $Destination
    }
}

function New-Zip([string]$Source, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination) { Remove-Item -Force -LiteralPath $Destination }
    Compress-Archive -Path (Join-Path $Source "*") -DestinationPath $Destination -CompressionLevel Optimal
}

function Get-LinuxRuntimeArchive {
    $downloadRoot = Join-Path $repoRoot ".tools\packaging"
    $archive = Join-Path $downloadRoot "OpenJDK21U-jre_x64_linux_hotspot_21.0.12_8.tar.gz"
    $checksum = "8a379a67c91a3ae61ffb33d46e0a40c7ba35e70713c4db31cfca30492f792eff"
    $url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jre_x64_linux_hotspot_21.0.12_8.tar.gz"
    New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading the pinned Temurin 21 Linux x64 runtime..."
        Invoke-Checked "curl.exe" @("--fail", "--location", "--output", $archive, $url)
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    if ($actual -ne $checksum) {
        throw "The downloaded Linux runtime checksum is invalid: $archive"
    }
    return $archive
}

function Expand-TarGzipMaterialized([string]$Archive, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $destinationRoot = [System.IO.Path]::GetFullPath($Destination).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $pendingLinks = [System.Collections.Generic.List[object]]::new()
    $file = [System.IO.File]::OpenRead($Archive)
    $gzip = [System.IO.Compression.GZipStream]::new(
        $file, [System.IO.Compression.CompressionMode]::Decompress)
    $reader = [System.Formats.Tar.TarReader]::new($gzip)
    try {
        while (($entry = $reader.GetNextEntry()) -ne $null) {
            $relative = $entry.Name.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
            $target = [System.IO.Path]::GetFullPath((Join-Path $destinationRoot $relative))
            if (-not $target.StartsWith(
                    $destinationRoot + [System.IO.Path]::DirectorySeparatorChar,
                    [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Linux runtime archive entry escapes its destination: $($entry.Name)"
            }
            switch ([string]$entry.EntryType) {
                "Directory" {
                    New-Item -ItemType Directory -Force -Path $target | Out-Null
                }
                "RegularFile" {
                    $parent = Split-Path -Parent $target
                    New-Item -ItemType Directory -Force -Path $parent | Out-Null
                    $output = [System.IO.File]::Open(
                        $target,
                        [System.IO.FileMode]::Create,
                        [System.IO.FileAccess]::Write,
                        [System.IO.FileShare]::None)
                    try { $entry.DataStream.CopyTo($output) } finally { $output.Dispose() }
                }
                "SymbolicLink" {
                    $pendingLinks.Add([pscustomobject]@{
                        Target = $target
                        LinkName = $entry.LinkName
                    })
                }
                default {
                    throw "Unsupported Linux runtime archive entry type: $($entry.EntryType)"
                }
            }
        }
    } finally {
        $reader.Dispose()
        $gzip.Dispose()
        $file.Dispose()
    }

    # ZIP does not preserve Unix links, so materialize each legal-file symlink as a normal file.
    foreach ($link in $pendingLinks) {
        if ([string]::IsNullOrWhiteSpace($link.LinkName) -or
                [System.IO.Path]::IsPathRooted($link.LinkName)) {
            throw "Linux runtime archive contains an unsafe symbolic link."
        }
        $linkValue = $link.LinkName.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $source = [System.IO.Path]::GetFullPath(
            (Join-Path (Split-Path -Parent $link.Target) $linkValue))
        if (-not $source.StartsWith(
                $destinationRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Linux runtime symbolic link has an unavailable target: $($link.LinkName)"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $link.Target) | Out-Null
        Copy-Item -Force -LiteralPath $source -Destination $link.Target
    }
}

$resolvedJdk = Resolve-Jdk21 $JdkHome
$jpackage = Join-Path $resolvedJdk "bin\jpackage.exe"
$jlink = Join-Path $resolvedJdk "bin\jlink.exe"

Assert-ChildPath $distRoot $repoRoot
Assert-ChildPath $buildRoot $repoRoot
if ($PlayerOnly) {
    New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
    $playerOutput = Join-Path $distRoot "dreamingfish-player-windows-x64"
    $playerArchive = Join-Path $distRoot "dreamingfish-player-windows-x64-$Version.zip"
    if (Test-Path -LiteralPath $playerOutput) {
        Remove-Item -Recurse -Force -LiteralPath $playerOutput
    }
    if (Test-Path -LiteralPath $playerArchive) {
        Remove-Item -Force -LiteralPath $playerArchive
    }
} elseif ($AdminOnly) {
    New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
    $adminOutputs = @(
        (Join-Path $distRoot "dfs-admin-windows-x64"),
        (Join-Path $distRoot "dfs-admin-linux-x64"),
        (Join-Path $distRoot "dfs-admin-windows-x64-$AdminVersion.zip"),
        (Join-Path $distRoot "dfs-admin-linux-x64-$AdminVersion.zip")
    )
    foreach ($adminOutput in $adminOutputs) {
        if (Test-Path -LiteralPath $adminOutput) {
            Remove-Item -Recurse -Force -LiteralPath $adminOutput
        }
    }
} elseif (Test-Path -LiteralPath $distRoot) {
    Remove-Item -Recurse -Force -LiteralPath $distRoot
}
if (Test-Path -LiteralPath $buildRoot) { Remove-Item -Recurse -Force -LiteralPath $buildRoot }
New-Item -ItemType Directory -Force -Path $distRoot, $buildRoot | Out-Null

$mavenArguments = @("clean", "package")
if ($PlayerOnly) { $mavenArguments += @("-pl", "player-app", "-am") }
if ($AdminOnly) { $mavenArguments += @("-pl", "management-cli", "-am") }
if ($SkipTests) { $mavenArguments += "-DskipTests" }
Invoke-Checked (Join-Path $repoRoot "mvnw.cmd") $mavenArguments

$adminJar = Join-Path $repoRoot "management-cli\target\dfs-admin.jar"
$agentJar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "bootstrap-agent\target") `
    -Filter "bootstrap-agent-*.jar" |
    Where-Object { $_.Name -notlike "original-*" } |
    Select-Object -First 1
$playerJar = if ($AdminOnly) { $null } else {
    Get-ChildItem -LiteralPath (Join-Path $repoRoot "player-app\target") `
        -Filter "player-app-*.jar" |
        Select-Object -First 1
}
$missingArtifacts = ($null -eq $agentJar) -or
        (-not $AdminOnly -and $null -eq $playerJar) -or
        (-not $PlayerOnly -and -not (Test-Path -LiteralPath $adminJar))
if ($missingArtifacts) {
    throw "Maven did not produce all required artifacts."
}

if (-not $AdminOnly) {
    # Build the Windows desktop app image with a private Java 21 runtime.
    $playerInput = Join-Path $buildRoot "player-input"
    New-Item -ItemType Directory -Force -Path $playerInput | Out-Null
    Copy-Item -LiteralPath $playerJar.FullName -Destination (Join-Path $playerInput "player-app.jar")
    Get-ChildItem -LiteralPath (Join-Path $repoRoot "player-app\target\runtime-dependencies") -Filter "*.jar" |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $playerInput }
    $playerImageRoot = Join-Path $buildRoot "player-image"
    New-Item -ItemType Directory -Force -Path $playerImageRoot | Out-Null
    Invoke-Checked $jpackage @(
        "--type", "app-image",
        "--name", "DreamingFishUpdater",
        "--dest", $playerImageRoot,
        "--input", $playerInput,
        "--main-jar", "player-app.jar",
        "--main-class", "cn.dreamingfish.updater.player.PlayerLauncher",
        "--app-version", $Version,
        "--vendor", "DreamingFish",
        "--description", "Minecraft modpack player updater",
        "--add-modules", "java.desktop,java.net.http,jdk.crypto.ec,jdk.unsupported",
        "--java-options", "-Dfile.encoding=UTF-8"
    )

    # jpackage can mark the Windows launcher read-only. Normalize the app image so
    # Maven clean, updater maintenance, and later packaging runs can remove it.
    Get-ChildItem -Recurse -Force -LiteralPath (Join-Path $playerImageRoot "DreamingFishUpdater") |
        Where-Object { -not $_.PSIsContainer -and $_.IsReadOnly } |
        ForEach-Object { $_.IsReadOnly = $false }

    $playerBundle = Join-Path $distRoot "dreamingfish-player-windows-x64"
    $bootstrapDirectory = Join-Path $playerBundle ".dreamingfish-bootstrap"
    $initialProgram = Join-Path $playerBundle "DreamingFishUpdater\app\$Version"
    $playerState = Join-Path $playerBundle "DreamingFishUpdater\state"
    New-Item -ItemType Directory -Force -Path $bootstrapDirectory, $initialProgram, $playerState | Out-Null
    Copy-Item -LiteralPath $agentJar.FullName -Destination (Join-Path $bootstrapDirectory "bootstrap-agent.jar")
    Copy-Item -LiteralPath (Join-Path $templates "project-binding.example.json") -Destination $bootstrapDirectory
    Copy-DirectoryContents (Join-Path $playerImageRoot "DreamingFishUpdater") $initialProgram
    $activeTemplate = Get-Content -Raw -LiteralPath (Join-Path $templates "active-player.properties")
    $activeTemplate = $activeTemplate.Replace("@VERSION@", $Version)
    [System.IO.File]::WriteAllText((Join-Path $playerState "active-player.properties"),
        $activeTemplate, [System.Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $templates "minecraft-jvm-argument.txt") -Destination $playerBundle
    Copy-Item -LiteralPath (Join-Path $templates "README-player.txt") -Destination (Join-Path $playerBundle "README.txt")
    New-Zip $playerBundle (Join-Path $distRoot "dreamingfish-player-windows-x64-$Version.zip")
}

if ($PlayerOnly) {
    Write-Host "Player distribution created in $distRoot"
    return
}

# Build the Windows management app image with a native console launcher.
$adminWindows = Join-Path $distRoot "dfs-admin-windows-x64"
New-Item -ItemType Directory -Force -Path $adminWindows | Out-Null
$adminInput = Join-Path $buildRoot "admin-input"
$adminImageRoot = Join-Path $buildRoot "admin-image"
New-Item -ItemType Directory -Force -Path $adminInput, $adminImageRoot | Out-Null
Copy-Item -LiteralPath $adminJar -Destination (Join-Path $adminInput "dfs-admin.jar")
Invoke-Checked $jpackage @(
    "--type", "app-image",
    "--name", "DreamingFishAdmin",
    "--dest", $adminImageRoot,
    "--input", $adminInput,
    "--main-jar", "dfs-admin.jar",
    "--main-class", "cn.dreamingfish.updater.management.cli.ManagementCli",
    "--app-version", $AdminVersion,
    "--vendor", "DreamingFish",
    "--description", "DreamingFish modpack update management",
    "--win-console",
    "--add-modules", "java.se,jdk.httpserver,jdk.crypto.ec,jdk.unsupported",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", '-Ddfs.home=$APPDIR/..'
)
Copy-DirectoryContents (Join-Path $adminImageRoot "DreamingFishAdmin") $adminWindows
New-Item -ItemType Directory -Force -Path (Join-Path $adminWindows "support") | Out-Null
Copy-Item -LiteralPath $agentJar.FullName -Destination `
    (Join-Path $adminWindows "support\bootstrap-agent.jar")
Copy-Item -LiteralPath (Join-Path $templates "README-management.txt") -Destination (Join-Path $adminWindows "README.txt")
Copy-Item -LiteralPath (Join-Path $repoRoot "docs\QUICKSTART.md") -Destination (Join-Path $adminWindows "QUICKSTART.md")
Copy-Item -LiteralPath (Join-Path $repoRoot "docs\DEPLOYMENT.md") -Destination (Join-Path $adminWindows "DEPLOYMENT.md")
$adminVersionOutput = & (Join-Path $adminWindows "DreamingFishAdmin.exe") --version 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Packaged admin version check failed: $($adminVersionOutput -join [Environment]::NewLine)"
}
$expectedAdminVersion = "DreamingFish Update System $AdminVersion"
if (($adminVersionOutput -join " ").Trim() -ne $expectedAdminVersion) {
    throw "Packaged admin reported the wrong version; expected '$expectedAdminVersion', got '$($adminVersionOutput -join ' ')'"
}
New-Zip $adminWindows (Join-Path $distRoot "dfs-admin-windows-x64-$AdminVersion.zip")

if (-not $SkipLinux) {
    # Linux cannot use a Windows runtime. Package a verified upstream Linux Java 21 runtime instead.
    $linuxArchive = Get-LinuxRuntimeArchive
    $adminLinux = Join-Path $distRoot "dfs-admin-linux-x64"
    $linuxExtract = Join-Path $buildRoot "linux-runtime"
    New-Item -ItemType Directory -Force -Path (Join-Path $adminLinux "app"), $linuxExtract | Out-Null
    Expand-TarGzipMaterialized $linuxArchive $linuxExtract
    $linuxRuntimeSource = Get-ChildItem -Directory -LiteralPath $linuxExtract |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java") } |
        Select-Object -First 1
    if ($null -eq $linuxRuntimeSource) { throw "The Linux runtime archive has an unexpected layout." }
    Copy-DirectoryContents $linuxRuntimeSource.FullName (Join-Path $adminLinux "runtime")
    Copy-Item -LiteralPath $adminJar -Destination (Join-Path $adminLinux "app\dfs-admin.jar")
    New-Item -ItemType Directory -Force -Path (Join-Path $adminLinux "support") | Out-Null
    Copy-Item -LiteralPath $agentJar.FullName -Destination `
        (Join-Path $adminLinux "support\bootstrap-agent.jar")
    $linuxLauncher = Join-Path $adminLinux "dfs-admin"
    Copy-Item -LiteralPath (Join-Path $templates "dfs-admin") -Destination $linuxLauncher
    $linuxLauncherText = Get-Content -Raw -LiteralPath $linuxLauncher
    if (-not $linuxLauncherText.Contains("-Djava.net.preferIPv4Stack=true")) {
        throw "The Linux admin launcher must force the IPv4 network stack."
    }
    Copy-Item -LiteralPath (Join-Path $templates "README-management-linux.txt") `
        -Destination (Join-Path $adminLinux "README.txt")
    Copy-Item -LiteralPath (Join-Path $repoRoot "docs\QUICKSTART.md") `
        -Destination (Join-Path $adminLinux "QUICKSTART.md")
    Copy-Item -LiteralPath (Join-Path $repoRoot "docs\DEPLOYMENT.md") `
        -Destination (Join-Path $adminLinux "DEPLOYMENT.md")
    New-Zip $adminLinux (Join-Path $distRoot "dfs-admin-linux-x64-$AdminVersion.zip")
}

Write-Host "Distributions created in $distRoot"
