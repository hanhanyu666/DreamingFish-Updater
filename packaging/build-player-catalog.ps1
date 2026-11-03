[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$NotesFile,
    [string]$DistDirectory='',
    [string]$PrivateKey=$env:DFS_PLAYER_SIGNING_KEY,
    [string]$Repository='hanhanyu666/DreamingFish-Updater',
    [string]$MinimumBootstrapVersion='0.1.2',
    [string]$MinimumManagementVersion='0.3.2'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$catalogRepo=Split-Path -Parent $PSScriptRoot
if(-not $DistDirectory){$DistDirectory=Join-Path $catalogRepo 'dist'}
if(-not $PrivateKey){$PrivateKey=Join-Path $catalogRepo '.tools/release-signing/official-player.pk8'}
if(-not(Test-Path -LiteralPath $PrivateKey -PathType Leaf)){throw 'Official player signing key is required; retain it privately and set DFS_PLAYER_SIGNING_KEY.'}
if($Tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or $Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'){throw 'Invalid release identity'}
$catalogJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java'}
$catalogJar=Join-Path $catalogRepo 'management-cli/target/dfs-admin.jar'
$catalogPublic=Join-Path $catalogRepo 'management-core/src/main/resources/cn/dreamingfish/updater/management/official-player-public-key.txt'
$catalogProgram=Join-Path $DistDirectory "dreamingfish-player-windows-x64/DreamingFishUpdater/app/$Version"
$catalogArchive=Join-Path $DistDirectory "dreamingfish-player-windows-x64-$Version.zip"
$catalogUrl="https://github.com/$Repository/releases/download/$Tag/$([IO.Path]::GetFileName($catalogArchive))"
& $catalogJava -cp $catalogJar cn.dreamingfish.updater.management.OfficialPlayerReleaseTool sign $PrivateKey $catalogPublic $catalogProgram $catalogArchive $catalogUrl $NotesFile $MinimumBootstrapVersion $MinimumManagementVersion
if($LASTEXITCODE -ne 0){throw 'Official player catalogue signing failed'}
& $catalogJava -cp $catalogJar cn.dreamingfish.updater.management.OfficialPlayerReleaseTool verify (Join-Path $DistDirectory 'player-catalog.signed') $catalogPublic $DistDirectory $Version "https://github.com/$Repository/releases/download/$Tag/"
if($LASTEXITCODE -ne 0){throw 'Official player catalogue verification failed'}
