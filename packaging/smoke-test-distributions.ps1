[CmdletBinding()]
param(
    [string]$Version = "0.1.27",
    [string]$AdminVersion = "0.1.18",
    [string]$DistDirectory = "",
    [string]$JdkHome = "",
    [switch]$KeepWork
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$packagingRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $packagingRoot
$distRoot = if ([string]::IsNullOrWhiteSpace($DistDirectory)) {
    Join-Path $repoRoot "dist"
} else {
    [System.IO.Path]::GetFullPath($DistDirectory)
}
$resolvedAdminVersion = if ([string]::IsNullOrWhiteSpace($AdminVersion)) {
    $Version
} else {
    $AdminVersion
}
$smokeParent = Join-Path $repoRoot "target\distribution-smoke"
$workRoot = Join-Path $smokeParent ([Guid]::NewGuid().ToString("N"))
$adminRoot = Join-Path $workRoot "admin"
$playerTemplate = Join-Path $workRoot "player-template"
$serverProcess = $null
$succeeded = $false

function Assert-ChildPath([string]$Path, [string]$Parent) {
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $resolvedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if (-not $resolvedPath.StartsWith(
            $resolvedParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Expected a path below $resolvedParent, got $resolvedPath"
    }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Resolve-TestJava([string]$Requested) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Requested)) { $candidates += $Requested }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates += $env:JAVA_HOME }
    $candidates += "D:\Program Files\Java\jdk-21"
    $candidates += "C:\Program Files\Java\jdk-21"
    foreach ($candidate in $candidates | Select-Object -Unique) {
        $java = Join-Path $candidate "bin\java.exe"
        if (Test-Path -LiteralPath $java -PathType Leaf) { return $java }
    }
    throw "A JDK is required for the standalone bootstrap verifier. Pass -JdkHome <path>."
}

function Invoke-Admin([string[]]$AdminArguments) {
    $output = & $script:adminCommand @AdminArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "dfs-admin failed ($LASTEXITCODE): $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Publish-Release([string]$DisplayVersion, [string]$Changelog) {
    Invoke-Admin @("project", "scan", "smoke-pack") | Out-Null
    $output = Invoke-Admin @(
        "project", "publish", "smoke-pack",
        "--version", $DisplayVersion,
        "--minimum-player-version", $Version,
        "--changelog", $Changelog,
        "--removed-files", "DELETE",
        "--yes"
    )
    $text = $output -join [Environment]::NewLine
    $match = [regex]::Match($text, "Published\s+\S+\s+as\s+(\S+)\s+\(sequence")
    if (-not $match.Success) {
        throw "Unable to read the release ID from dfs-admin output: $text"
    }
    return $match.Groups[1].Value
}

function Copy-PlayerTemplate([string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -Force -LiteralPath $script:playerTemplate | ForEach-Object {
        Copy-Item -Recurse -Force -LiteralPath $_.FullName -Destination $Destination
    }
}

function Read-ActivePlayer([string]$Instance) {
    $path = Join-Path $Instance "DreamingFishUpdater\state\active-player.properties"
    return ConvertFrom-StringData (Get-Content -Raw -LiteralPath $path)
}

function Verify-PlayerProgram([string]$Instance, [bool]$ExpectSuccess) {
    $binding = Get-Content -Raw -LiteralPath (Join-Path $Instance `
            ".dreamingfish-bootstrap\project-binding.json") | ConvertFrom-Json
    $active = Read-ActivePlayer $Instance
    $agent = Join-Path $Instance ".dreamingfish-bootstrap\bootstrap-agent.jar"
    $playerHome = Join-Path $Instance "DreamingFishUpdater"
    $output = & $script:testJava -cp $agent `
        "cn.dreamingfish.updater.bootstrap.PlayerProgramVerifierMain" `
        $playerHome $binding.projectId $binding.publicKey `
        $active.version $active.manifestSha256 2>&1
    $exitCode = $LASTEXITCODE
    if ($ExpectSuccess -and $exitCode -ne 0) {
        throw "Packaged player verification failed: $($output -join [Environment]::NewLine)"
    }
    if (-not $ExpectSuccess -and $exitCode -eq 0) {
        throw "Packaged player verification accepted a modified program"
    }
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

try {
    $testJava = Resolve-TestJava $JdkHome
    $adminZip = Join-Path $distRoot "dfs-admin-windows-x64-$resolvedAdminVersion.zip"
    $playerZip = Join-Path $distRoot "dreamingfish-player-windows-x64-$Version.zip"
    Assert-True (Test-Path -LiteralPath $adminZip -PathType Leaf) "Missing admin ZIP: $adminZip"
    Assert-True (Test-Path -LiteralPath $playerZip -PathType Leaf) "Missing player ZIP: $playerZip"
    Assert-ChildPath $workRoot $smokeParent
    New-Item -ItemType Directory -Force -Path $adminRoot, $playerTemplate | Out-Null
    Expand-Archive -LiteralPath $adminZip -DestinationPath $adminRoot
    Expand-Archive -LiteralPath $playerZip -DestinationPath $playerTemplate

    $adminCommand = Join-Path $adminRoot "DreamingFishAdmin.exe"
    $versionOutput = (Invoke-Admin @("--version")) -join " "
    Assert-True ($versionOutput -eq "DreamingFish Update System $resolvedAdminVersion") `
        "Packaged admin reported the wrong version: $versionOutput"
    Assert-True (Test-Path -LiteralPath (Join-Path $playerTemplate `
            ".dreamingfish-bootstrap\bootstrap-agent.jar") -PathType Leaf) `
        "Player ZIP is missing bootstrap-agent.jar"
    Assert-True (Test-Path -LiteralPath (Join-Path $adminRoot `
            "support\bootstrap-agent.jar") -PathType Leaf) `
        "Admin ZIP is missing support/bootstrap-agent.jar"
    Assert-True (Test-Path -LiteralPath (Join-Path $playerTemplate `
            "DreamingFishUpdater\app\$Version\DreamingFishUpdater.exe") -PathType Leaf) `
        "Player ZIP is missing DreamingFishUpdater.exe"
    Assert-True (Test-Path -LiteralPath (Join-Path $playerTemplate `
            "DreamingFishUpdater\app\$Version\runtime\bin\java.dll") -PathType Leaf) `
        "Player ZIP is missing its private desktop runtime"

    $port = Get-FreeTcpPort
    $source = Join-Path $workRoot "standard-pack"
    $mods = Join-Path $source "mods"
    $config = Join-Path $source "config"
    New-Item -ItemType Directory -Force -Path $mods, $config | Out-Null
    $smallAsset = Join-Path $repoRoot "README.md"
    $largeAsset = Join-Path $repoRoot "docs\V1-DESIGN.md"
    for ($index = 0; $index -lt 10; $index++) {
        Copy-Item -Force -LiteralPath $smallAsset `
            -Destination (Join-Path $mods "original-$index.jar")
    }
    Copy-Item -Force -LiteralPath $largeAsset `
        -Destination (Join-Path $config "server-settings.toml")

    Invoke-Admin @(
        "project", "create", "smoke-pack",
        "--name", "Smoke Pack",
        "--source", $source,
        "--public-url", "http://127.0.0.1:$port",
        "--force-sync-directories", "mods"
    ) | Out-Null
    Invoke-Admin @(
        "player", "publish", "smoke-pack",
        "--platform", "windows-x64",
        "--source", $playerTemplate,
        "--minimum-bootstrap-version", "0.1.2",
        "--yes"
    ) | Out-Null

    $releaseOne = Publish-Release "1.1.0" "Initial ten mods"
    $deploymentParent = Join-Path $workRoot "thin-deployment"
    New-Item -ItemType Directory -Force -Path $deploymentParent | Out-Null
    Invoke-Admin @(
        "project", "deployment", "smoke-pack",
        "--output", $deploymentParent,
        "--release", $releaseOne,
        "--platform", "windows-x64",
        "--yes"
    ) | Out-Null
    $thinDeployment = Join-Path $deploymentParent `
        "smoke-pack-player-deployment-1.1.0-$Version"
    Assert-True (Test-Path -LiteralPath (Join-Path $thinDeployment `
            ".dreamingfish-bootstrap\bundled-release\manifest.sig") -PathType Leaf) `
        "Thin deployment is missing its signed release baseline"
    Assert-True (Test-Path -LiteralPath (Join-Path $thinDeployment `
            "DreamingFishUpdater\app\$Version\DreamingFishUpdater.exe") -PathType Leaf) `
        "Thin deployment is missing its player program"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $thinDeployment "mods"))) `
        "Thin deployment unexpectedly contains managed modpack files"
    Verify-PlayerProgram $thinDeployment $true
    $instanceOne = Join-Path $workRoot "instance-from-1.1"
    Copy-PlayerTemplate $instanceOne
    Invoke-Admin @(
        "project", "binding", "smoke-pack",
        "--instance", $instanceOne,
        "--platform", "windows-x64",
        "--release", $releaseOne
    ) | Out-Null

    for ($index = 0; $index -lt 5; $index++) {
        Copy-Item -Force -LiteralPath $largeAsset `
            -Destination (Join-Path $mods "addition-$index.jar")
    }
    $releaseTwo = Publish-Release "1.2.0" "Add five mods"
    $instanceTwo = Join-Path $workRoot "instance-from-1.2"
    Copy-PlayerTemplate $instanceTwo
    Invoke-Admin @(
        "project", "binding", "smoke-pack",
        "--instance", $instanceTwo,
        "--platform", "windows-x64",
        "--release", $releaseTwo
    ) | Out-Null

    for ($index = 0; $index -lt 8; $index++) {
        $target = Join-Path $mods "original-$index.jar"
        Assert-True (Test-Path -LiteralPath $target -PathType Leaf) `
            "Expected test mod is missing before deletion: $target"
        Remove-Item -Force -LiteralPath $target
    }
    $releaseThreeChangelog = "删除八个旧模组，保留五个新增模组"
    $releaseThree = Publish-Release "1.3.0" $releaseThreeChangelog

    $baselineOne = Get-Content -Raw -LiteralPath (Join-Path $instanceOne `
            ".dreamingfish-bootstrap\bundled-release\manifest.json") | ConvertFrom-Json
    $baselineTwo = Get-Content -Raw -LiteralPath (Join-Path $instanceTwo `
            ".dreamingfish-bootstrap\bundled-release\manifest.json") | ConvertFrom-Json
    $modsOne = (Get-ChildItem -File -LiteralPath (Join-Path $instanceOne "mods")).Count
    $modsTwo = (Get-ChildItem -File -LiteralPath (Join-Path $instanceTwo "mods")).Count
    Assert-True ($modsOne -eq 10) "The 1.1 distributable does not contain ten mods"
    Assert-True ($modsTwo -eq 15) "The 1.2 distributable does not contain fifteen mods"
    Assert-True ($baselineOne.releaseId -eq $releaseOne) `
        "The 1.1 distributable has the wrong bundled baseline"
    Assert-True ($baselineTwo.releaseId -eq $releaseTwo) `
        "The 1.2 distributable has the wrong bundled baseline"

    Verify-PlayerProgram $instanceOne $true
    $programRoot = Join-Path $instanceOne "DreamingFishUpdater\app\$Version"
    $programFile = @(
        (Join-Path $programRoot "player-sidecar.jar"),
        (Join-Path $programRoot "app\player-app.jar")
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    Assert-True (-not [string]::IsNullOrWhiteSpace($programFile)) `
        "Player distribution contains neither the Tauri sidecar nor the legacy player JAR"
    $savedProgramFile = Join-Path $workRoot (Split-Path -Leaf $programFile)
    Copy-Item -Force -LiteralPath $programFile -Destination $savedProgramFile
    try {
        Copy-Item -Force -LiteralPath $smallAsset -Destination $programFile
        Verify-PlayerProgram $instanceOne $false
    } finally {
        Copy-Item -Force -LiteralPath $savedProgramFile -Destination $programFile
    }
    Verify-PlayerProgram $instanceOne $true
    Verify-PlayerProgram $instanceTwo $true

    $serverOut = Join-Path $workRoot "server.out.log"
    $serverErr = Join-Path $workRoot "server.err.log"
    $serverProcess = Start-Process -FilePath $adminCommand -ArgumentList @(
        "serve", "--host", "127.0.0.1", "--port", $port
    ) -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr `
        -WindowStyle Hidden -PassThru
    $health = $null
    for ($attempt = 0; $attempt -lt 50 -and $null -eq $health; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/healthz" `
                -TimeoutSec 1
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Assert-True ($null -ne $health -and $health.status -eq "ok") `
        "Packaged HTTP service did not become healthy"
    $latestResponse = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$port/v1/projects/smoke-pack/latest" `
        -TimeoutSec 5
    $latest = $latestResponse.Content | ConvertFrom-Json
    $signature = $latestResponse.Headers["X-Dfs-Signature"]
    Assert-True ($latest.releaseId -eq $releaseThree) `
        "The HTTP endpoint did not return release 1.3"
    Assert-True ($latest.displayVersion -eq "1.3.0") `
        "The HTTP endpoint returned the wrong display version"
    Assert-True ($latest.forcedSyncDirectories -contains "mods") `
        "The HTTP manifest omitted forced mods synchronization"
    Assert-True (-not [string]::IsNullOrWhiteSpace($signature)) `
        "The HTTP manifest response is missing its signature"
    $latestModCount = @($latest.files | Where-Object { $_.path -like "mods/*" }).Count
    Assert-True ($latestModCount -eq 7) `
        "Release 1.3 should contain two retained and five added mods"
    $history = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$port/v1/projects/smoke-pack/history" `
        -TimeoutSec 5
    Assert-True (@($history.releases).Count -eq 3) `
        "The packaged history endpoint did not return all three releases"
    Assert-True ($history.releases[0].releaseId -eq $releaseThree) `
        "The packaged history endpoint is not newest-first"
    Assert-True ($history.releases[0].changelog -eq $releaseThreeChangelog) `
        "The packaged history endpoint did not preserve the Chinese changelog"

    $succeeded = $true
    [pscustomobject]@{
        AdminVersion = $versionOutput
        AdminDataAutoCreated = Test-Path -LiteralPath `
            (Join-Path $adminRoot "data\management.db")
        Release11Mods = $modsOne
        Release12Mods = $modsTwo
        Release13Mods = $latestModCount
        HistoricalBaselinesSigned = (
            (Test-Path -LiteralPath (Join-Path $instanceOne `
                ".dreamingfish-bootstrap\bundled-release\manifest.sig")) -and
            (Test-Path -LiteralPath (Join-Path $instanceTwo `
                ".dreamingfish-bootstrap\bundled-release\manifest.sig"))
        )
        ThinDeploymentVerified = $true
        PlayerProgramVerification = "valid accepted; modified rejected; restored accepted"
        ForcedSyncDirectories = $latest.forcedSyncDirectories -join ","
        HttpHealth = $health.status
        HttpLatestVersion = $latest.displayVersion
        HttpHistoryReleases = @($history.releases).Count
        HttpSignaturePresent = -not [string]::IsNullOrWhiteSpace($signature)
        AdminZipSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $adminZip).Hash
        PlayerZipSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $playerZip).Hash
        WorkDirectory = if ($KeepWork) { $workRoot } else { "removed after success" }
    }
} finally {
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id
        $serverProcess.WaitForExit()
    }
    if ($succeeded -and -not $KeepWork -and (Test-Path -LiteralPath $workRoot)) {
        Assert-ChildPath $workRoot $smokeParent
        Remove-Item -Recurse -Force -LiteralPath $workRoot
    } elseif (-not $succeeded) {
        Write-Warning "Distribution smoke test data retained at $workRoot"
    }
}
