param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipBuildCore,
    [switch]$SkipTests,
    [switch]$PortableOnly,
    # Private key that signs the installer for auto-update. Keep it out of the repository and back it up:
    # installed copies only accept updates signed by it.
    [string]$SigningKey = (Join-Path $env:USERPROFILE '.tauri\dreamingfish-admin-desktop.key'),
    # Local test builds without the key; such installers cannot be offered as updates.
    [switch]$NoUpdaterSignature,
    # Release tag whose assets latest.json points at; defaults to v<desktop version>.
    [string]$ReleaseTag = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$desktopRepo = Split-Path -Parent $PSScriptRoot
$desktopUi = Join-Path $desktopRepo 'admin-desktop'
$desktopCore = Join-Path $desktopUi 'src-tauri\resources\core'
$desktopDist = Join-Path $desktopRepo 'dist'
$desktopVersion = (Get-Content -LiteralPath (Join-Path $desktopUi 'package.json') -Raw | ConvertFrom-Json).version
function Invoke-DesktopTool([string]$Executable, [string[]]$ToolArgs) {
    & $Executable @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Desktop build command failed: $Executable" }
}
# The first paragraph of this version's CHANGELOG section, shown in the update prompt.
function Get-DesktopReleaseNotes([string]$Version) {
    $lines = @(Get-Content -LiteralPath (Join-Path $desktopRepo 'CHANGELOG.md') -Encoding utf8)
    $paragraph = @()
    $inside = $false
    foreach ($line in $lines) {
        if ($line.StartsWith('## ')) { if ($inside) { break }; $inside = $line.StartsWith("## [$Version]"); continue }
        if (-not $inside) { continue }
        if ($line.Trim() -eq '') { if ($paragraph.Count) { break } else { continue } }
        $paragraph += $line.Trim()
    }
    return (($paragraph -join '') -replace '\*\*', '')
}
function Assert-DesktopChild([string]$Target, [string]$Parent) {
    $absolute = [IO.Path]::GetFullPath($Target)
    $base = [IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) { throw "Unexpected desktop build path: $absolute" }
    if ((Test-Path -LiteralPath $absolute) -and ((Get-Item -LiteralPath $absolute -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Linked desktop build directory: $absolute" }
}
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome with a complete Java 21 JDK' }
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\jlink.exe'))) { throw 'A complete Java 21 JDK is required' }
if ((Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw) -notmatch 'JAVA_VERSION="21\.') { throw 'Use Java 21 to build the desktop runtime' }
$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH
Push-Location $desktopRepo
try {
    if (-not $SkipBuildCore) {
        Invoke-DesktopTool 'npm.cmd' @('--prefix','player-ui','run','build')
        Invoke-DesktopTool 'npm.cmd' @('--prefix','admin-desktop','run','build:web')
        $coreArgs = @('-pl','management-cli','-am','package')
        if ($SkipTests) {$coreArgs += '-DskipTests'}
        Invoke-DesktopTool (Join-Path $desktopRepo 'mvnw.cmd') $coreArgs
    }
    New-Item -ItemType Directory -Path (Join-Path $desktopCore 'support') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $desktopRepo 'LICENSE') -Destination (Join-Path $desktopUi 'src-tauri\resources\LICENSE') -Force
    Copy-Item -LiteralPath (Join-Path $desktopRepo 'management-cli\target\dfs-admin.jar') -Destination (Join-Path $desktopCore 'dfs-admin.jar') -Force
    # Each desktop distribution contains only the SQLite native library for its platform.
    $desktopJar = [IO.Compression.ZipFile]::Open((Join-Path $desktopCore 'dfs-admin.jar'),[IO.Compression.ZipArchiveMode]::Update)
    try {
        $sqliteUnused = @($desktopJar.Entries | Where-Object { $_.FullName.StartsWith('org/sqlite/native/',[StringComparison]::Ordinal) -and -not $_.FullName.StartsWith('org/sqlite/native/Windows/x86_64/',[StringComparison]::Ordinal) })
        foreach ($entry in $sqliteUnused) {$entry.Delete()}
    } finally {$desktopJar.Dispose()}
    $desktopAgent = Get-ChildItem -LiteralPath (Join-Path $desktopRepo 'bootstrap-agent\target') -File -Filter 'bootstrap-agent-*.jar' | Where-Object {$_.Name -notlike 'original-*'} | Select-Object -First 1
    if (-not $desktopAgent) {throw 'Bootstrap Agent has not been built'}
    Copy-Item -LiteralPath $desktopAgent.FullName -Destination (Join-Path $desktopCore 'support\bootstrap-agent.jar') -Force
    $runtimeBuild = Join-Path $desktopRepo ('target\packaging-admin-desktop\runtime-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($runtimeBuild)) -Force | Out-Null
    Invoke-DesktopTool (Join-Path $JdkHome 'bin\jlink.exe') @('--add-modules','java.naming,java.net.http,java.sql,jdk.httpserver,jdk.crypto.ec,jdk.unsupported','--strip-debug','--no-man-pages','--no-header-files','--compress=2','--output',$runtimeBuild)
    foreach ($extra in @('lib\jvm.lib','bin\javaw.exe')) { $extraPath = Join-Path $runtimeBuild $extra; if(Test-Path -LiteralPath $extraPath){Remove-Item -LiteralPath $extraPath -Force} }
    $runtimeTarget = Join-Path $desktopCore 'runtime'
    Assert-DesktopChild $runtimeTarget $desktopUi
    if(Test-Path -LiteralPath $runtimeTarget){Remove-Item -LiteralPath $runtimeTarget -Recurse -Force}
    Copy-Item -LiteralPath $runtimeBuild -Destination $runtimeTarget -Recurse
    $coreVersion = & (Join-Path $runtimeTarget 'bin\java.exe') -jar (Join-Path $desktopCore 'dfs-admin.jar') --version
    if ($LASTEXITCODE -ne 0 -or "$coreVersion" -notmatch [regex]::Escape($desktopVersion)) {throw 'Bundled management core version mismatch'}
    Push-Location $desktopUi
    try {
        if(-not(Test-Path -LiteralPath 'node_modules')){Invoke-DesktopTool 'npm.cmd' @('ci')}
        Invoke-DesktopTool 'npm.cmd' @('run','build')
        if(-not $SkipTests){Invoke-DesktopTool 'npm.cmd' @('test');Invoke-DesktopTool 'cargo' @('test','--locked','--manifest-path','src-tauri/Cargo.toml')}
        # --ci keeps the build unattended; the signing key has no password and would otherwise prompt for one.
        $desktopTauriArgs = @('run','tauri','--','build','--ci')
        if($PortableOnly){$desktopTauriArgs += '--no-bundle'}
        elseif($NoUpdaterSignature){
            $unsigned = Join-Path $desktopRepo 'target\packaging-admin-desktop\no-updater.json'
            [IO.File]::WriteAllText($unsigned, '{"bundle":{"createUpdaterArtifacts":false}}')
            $desktopTauriArgs += @('--config',$unsigned)
        }
        else{
            if(-not (Test-Path -LiteralPath $SigningKey -PathType Leaf)){throw "Updater signing key not found: $SigningKey (pass -NoUpdaterSignature for a local test build)"}
            $env:TAURI_SIGNING_PRIVATE_KEY = $SigningKey
        }
        Invoke-DesktopTool 'npm.cmd' $desktopTauriArgs
    } finally {Pop-Location}
    $desktopPackage = Join-Path $desktopDist 'dfs-admin-desktop-windows-x64'
    Assert-DesktopChild $desktopPackage $desktopDist
    if(Test-Path -LiteralPath $desktopPackage){Remove-Item -LiteralPath $desktopPackage -Recurse -Force}
    New-Item -ItemType Directory -Path $desktopPackage -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $desktopUi 'src-tauri\target\release\dreamingfish-admin-desktop.exe') -Destination (Join-Path $desktopPackage 'DreamingFishAdminDesktop.exe')
    Copy-Item -LiteralPath $desktopCore -Destination (Join-Path $desktopPackage 'core') -Recurse
    Copy-Item -LiteralPath (Join-Path $desktopRepo 'packaging\templates\README-admin-desktop.txt') -Destination (Join-Path $desktopPackage 'README.txt')
    Copy-Item -LiteralPath (Join-Path $desktopRepo 'LICENSE') -Destination (Join-Path $desktopPackage 'LICENSE')
    $desktopZip = Join-Path $desktopDist "dfs-admin-desktop-windows-x64-$desktopVersion.zip"
    if(Test-Path -LiteralPath $desktopZip){Remove-Item -LiteralPath $desktopZip -Force}
    Compress-Archive -Path (Join-Path $desktopPackage '*') -DestinationPath $desktopZip -CompressionLevel Optimal
    if(-not $PortableOnly){
        $desktopInstaller = Get-ChildItem -LiteralPath (Join-Path $desktopUi 'src-tauri\target\release\bundle\nsis') -Filter '*-setup.exe' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if(-not $desktopInstaller){throw 'Desktop installer was not produced'}
        $installerName = "dfs-admin-desktop-windows-x64-$desktopVersion-setup.exe"
        Copy-Item -LiteralPath $desktopInstaller.FullName -Destination (Join-Path $desktopDist $installerName) -Force
        $latest = Join-Path $desktopDist 'latest.json'
        if(Test-Path -LiteralPath $latest){Remove-Item -LiteralPath $latest -Force}
        if(-not $NoUpdaterSignature){
            # Installed copies read this from the latest GitHub release to find and verify the update.
            $signature = Join-Path $desktopInstaller.DirectoryName ($desktopInstaller.Name + '.sig')
            if(-not (Test-Path -LiteralPath $signature)){throw 'Updater signature was not produced'}
            $tag = if($ReleaseTag){$ReleaseTag}else{"v$desktopVersion"}
            $manifest = [ordered]@{
                version = $desktopVersion
                notes = Get-DesktopReleaseNotes $desktopVersion
                pub_date = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
                platforms = [ordered]@{
                    'windows-x86_64' = [ordered]@{
                        signature = (Get-Content -LiteralPath $signature -Raw).Trim()
                        url = "https://github.com/hanhanyu666/DreamingFish-Updater/releases/download/$tag/$installerName"
                    }
                }
            }
            [IO.File]::WriteAllText($latest, ($manifest | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
        }
    }
    Get-ChildItem -LiteralPath $desktopDist -File | Where-Object {$_.Name -like 'dfs-admin-desktop-windows-x64-*' -or $_.Name -eq 'latest.json'} | Select-Object Name,Length
} finally {Pop-Location}
