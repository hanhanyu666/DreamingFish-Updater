[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenRepository = '',
    [string]$IsolatedRoot = '',
    [switch]$WithPackaging,
    [switch]$WithDesktopSmoke,
    [switch]$SkipNative
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$validationRepo=Split-Path -Parent $PSScriptRoot
$validationWindows=[Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)
if(-not $JdkHome){throw 'Provide -JdkHome or JAVA_HOME with Java 21'}
if((Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw) -notmatch 'JAVA_VERSION="21[.\"]'){throw 'Validation requires JDK 21'}
if(-not $MavenRepository){$MavenRepository=Join-Path $validationRepo '.m2/repository'}
$MavenRepository=[IO.Path]::GetFullPath($MavenRepository)

if($IsolatedRoot){
    $isolated=[IO.Path]::GetFullPath($IsolatedRoot)
    if(Test-Path -LiteralPath $isolated){throw 'The isolated build directory must be new; existing data is never cleaned'}
    [IO.Directory]::CreateDirectory($isolated)|Out-Null
    $sourcePrefix=[IO.Path]::GetFullPath($validationRepo).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    $targetPrefix=$isolated.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    $sourceFiles=@(& git -C $validationRepo -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if($LASTEXITCODE -ne 0){throw 'Could not enumerate the working source'}
    $allowedSourceRoots=@('protocol','update-engine','management-core','management-cli','bootstrap-agent','player-app','admin-desktop','player-ui','packaging','docs','assets','.github','.mvn')
    $allowedSourceFiles=@('pom.xml','README.md','CHANGELOG.md','CONTEXT.md','LICENSE','mvnw','mvnw.cmd','.gitignore','AGENTS.md')
    foreach($relative in $sourceFiles){
        if(($relative.Split('/')[0]-notin $allowedSourceRoots)-and($relative-notin $allowedSourceFiles)){continue}
        if($relative -match '(^|/)(node_modules|target|dist|web-dist|\.git)/'){continue}
        $source=[IO.Path]::GetFullPath((Join-Path $validationRepo $relative));$target=[IO.Path]::GetFullPath((Join-Path $isolated $relative))
        if(-not $source.StartsWith($sourcePrefix,[StringComparison]::OrdinalIgnoreCase) -or -not  $target.StartsWith($targetPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Source copy escaped its boundary'}
        if(-not(Test-Path -LiteralPath $source -PathType Leaf)){continue}
        if((Get-Item -LiteralPath $source -Force).Attributes-band[IO.FileAttributes]::ReparsePoint){throw "Linked build input: $relative"}
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
        [IO.File]::Copy($source,$target,$false)
    }
    # Verified tool downloads are caches, not previous compiled application outputs.
    $linuxArchive=Join-Path $validationRepo '.tools/packaging/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz'
    if(Test-Path -LiteralPath $linuxArchive){$toolDir=Join-Path $isolated '.tools/packaging';[IO.Directory]::CreateDirectory($toolDir)|Out-Null;Copy-Item -LiteralPath $linuxArchive -Destination $toolDir}
    $invoke=@{JdkHome=$JdkHome;MavenRepository=$MavenRepository;WithPackaging=$WithPackaging;WithDesktopSmoke=$WithDesktopSmoke;SkipNative=$SkipNative}
    Write-Host "Fresh source copy: $isolated"
    & (Join-Path $isolated 'packaging/validate.ps1') @invoke
    return
}

$validationRoot=Join-Path $validationRepo ('target/validation/'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($validationRoot)|Out-Null
$validationSteps=[Collections.Generic.List[object]]::new()
$validationSuccess=$false
$validationNpm=if($validationWindows){'npm.cmd'}else{'npm'}
$validationMaven=Join-Path $validationRepo $(if($validationWindows){'mvnw.cmd'}else{'mvnw'})
$env:JAVA_HOME=$JdkHome
$validationOldCargoTarget=$env:CARGO_TARGET_DIR
$env:CARGO_TARGET_DIR=Join-Path $validationRepo 'target/native-validation'
function Invoke-ValidationStep([string]$Name,[string]$Executable,[string[]]$Arguments){
    $log=Join-Path $validationRoot ($Name+'.log');$timer=[Diagnostics.Stopwatch]::StartNew()
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath $log
    $code=$LASTEXITCODE;$timer.Stop()
    $validationSteps.Add([pscustomobject]@{name=$Name;exitCode=$code;seconds=$timer.Elapsed.TotalSeconds;log=$log})
    if($code-ne 0){
        if($env:GITHUB_ACTIONS -eq 'true'){
            $detail=(Get-Content -LiteralPath $log -Tail 30)-join "`n"
            $detail=$detail.Replace('%','%25').Replace("`r",'%0D').Replace("`n",'%0A')
            Write-Output "::error title=Validation failed - $Name::$detail"
        }
        throw "Validation failed: $Name ($code); $log"
    }
}
Push-Location $validationRepo
try{
    Invoke-ValidationStep 'python-runtime' 'python' @('-c','import sys; assert sys.version_info >= (3, 12)')
    Invoke-ValidationStep 'task-contract' 'node' @('packaging/update-task-contract.mjs','--check')
    Invoke-ValidationStep 'task-contract-tests' 'node' @('--test','packaging/tests/task-contract.test.mjs')
    Invoke-ValidationStep 'resource-guard-tests' 'node' @('--test','packaging/tests/build-resources.test.mjs')
    Invoke-ValidationStep 'artifact-audit-tests' 'python' @('packaging/tests/artifact-audit.test.py')
    Invoke-ValidationStep 'player-ci' $validationNpm @('--prefix','player-ui','ci','--registry=https://registry.npmjs.org','--cache',(Join-Path $validationRoot 'npm-cache'))
    Invoke-ValidationStep 'player-tests' $validationNpm @('--prefix','player-ui','test')
    Invoke-ValidationStep 'player-build' $validationNpm @('--prefix','player-ui','run','build')
    Invoke-ValidationStep 'admin-ci' $validationNpm @('--prefix','admin-desktop','ci','--registry=https://registry.npmjs.org','--cache',(Join-Path $validationRoot 'npm-cache'))
    Invoke-ValidationStep 'admin-tests' $validationNpm @('--prefix','admin-desktop','test')
    Invoke-ValidationStep 'admin-web' $validationNpm @('--prefix','admin-desktop','run','build:web')
    Invoke-ValidationStep 'admin-desktop' $validationNpm @('--prefix','admin-desktop','run','build')
    & (Join-Path $validationRepo 'packaging/tests/publish-release.test.ps1') 2>&1 | Tee-Object -FilePath (Join-Path $validationRoot 'release-planning.log')
    Invoke-ValidationStep 'maven-verify' $validationMaven @('-B','-ntp',"-Dmaven.repo.local=$MavenRepository",'verify')
    $identity=(& node packaging/build-identity.mjs --print).Trim()
    $agent=Get-ChildItem -LiteralPath 'bootstrap-agent/target' -File -Filter 'bootstrap-agent-*.jar'|Where-Object{$_.Name-notlike'original-*'}|Select-Object -First 1
    $playerJar=Get-ChildItem -LiteralPath 'player-app/target' -File -Filter 'player-app-*.jar'|Where-Object{$_.Name-notlike'original-*'}|Select-Object -First 1
    Invoke-ValidationStep 'jar-identities' 'python' @('packaging/check-artifact-identity.py','--expected',$identity,'--admin-jar','management-cli/target/dfs-admin.jar','--player-jar',$playerJar.FullName,'--bootstrap-jar',$agent.FullName)
    if($validationWindows -and -not $SkipNative){
        $core=Join-Path $validationRepo 'admin-desktop/src-tauri/resources/core';[IO.Directory]::CreateDirectory((Join-Path $core 'support'))|Out-Null
        Copy-Item -LiteralPath 'management-cli/target/dfs-admin.jar' -Destination (Join-Path $core 'dfs-admin.jar')
        Copy-Item -LiteralPath $agent.FullName -Destination (Join-Path $core 'support/bootstrap-agent.jar')
        Copy-Item -LiteralPath 'LICENSE' -Destination 'admin-desktop/src-tauri/resources/LICENSE' -Force
        Invoke-ValidationStep 'core-identity' 'python' @('packaging/check-artifact-identity.py','--expected',$identity,'--core-directory',$core,'--write-core-info')
        Invoke-ValidationStep 'desktop-task-proxy-build' 'cargo' @('build','--locked','--manifest-path','packaging/tests/desktop-task-proxy/Cargo.toml')
        Invoke-ValidationStep 'desktop-task-contract' 'node' @('packaging/tests/desktop-task-contract.mjs','--java',(Join-Path $JdkHome 'bin/java.exe'),'--proxy',(Join-Path $env:CARGO_TARGET_DIR 'debug/task-contract-proxy.exe'),'--output',(Join-Path $validationRoot 'desktop-task-contract'))
        Invoke-ValidationStep 'admin-rust' 'cargo' @('test','--release','--locked','--manifest-path','admin-desktop/src-tauri/Cargo.toml')
        Invoke-ValidationStep 'player-rust' 'cargo' @('test','--release','--locked','--manifest-path','player-ui/src-tauri/Cargo.toml')
        if(-not $WithPackaging){
            Invoke-ValidationStep 'admin-release' 'cargo' @('build','--release','--locked','--features','custom-protocol','--manifest-path','admin-desktop/src-tauri/Cargo.toml')
            Invoke-ValidationStep 'player-release' 'cargo' @('build','--release','--locked','--features','custom-protocol','--manifest-path','player-ui/src-tauri/Cargo.toml')
        } # Packaging performs the production release builds with its final bundle configuration.
    }
    # Keep test evidence before packaging scripts rebuild standard module target directories.
    foreach($module in @('protocol','update-engine','management-core','management-cli','bootstrap-agent','player-app')){
        $reports=Join-Path $validationRepo "$module/target/surefire-reports"
        if(Test-Path -LiteralPath $reports){$saved=Join-Path $validationRoot "junit/$module";[IO.Directory]::CreateDirectory($saved)|Out-Null;Get-ChildItem -LiteralPath $reports -File -Filter '*.xml'|Copy-Item -Destination $saved}
    }
    if($WithPackaging){
        if(-not $validationWindows){throw 'Native packaging requires Windows'}
        $localDist=Join-Path $validationRepo 'target/validated-distributions'
        & (Join-Path $PSScriptRoot 'build-distributions.ps1') -JdkHome $JdkHome -MavenRepository $MavenRepository -SkipTests -OutputRoot $localDist
        & (Join-Path $PSScriptRoot 'build-admin-desktop.ps1') -JdkHome $JdkHome -MavenRepository $MavenRepository -SkipBuildCore -SkipTests -NoUpdaterSignature -OutputRoot $localDist
        Invoke-ValidationStep 'distribution-audit' 'python' @('packaging/audit-distributions.py','--dist',$localDist,'--include-desktop','--report',(Join-Path $validationRoot 'distribution-audit.json'))
        & (Join-Path $PSScriptRoot 'smoke-test-distributions.ps1') -JdkHome $JdkHome -DistDirectory $localDist -KeepWork
        Invoke-ValidationStep 'windows-launchers' 'python' @('packaging/tests/windows-launcher-smoke.py','--archive',(Join-Path $localDist 'dfs-admin-windows-x64-0.2.6.zip'),'--output',(Join-Path $validationRoot 'windows-launchers'))
        if($WithDesktopSmoke){Invoke-ValidationStep 'desktop-probe-dependencies' 'python' @('-c','import psutil');Invoke-ValidationStep 'desktop-smoke' 'python' @('packaging/desktop-remediation-smoke.py','--exe',(Join-Path $localDist 'dfs-admin-desktop-windows-x64/DreamingFishAdminDesktop.exe'),'--core',(Join-Path $localDist 'dfs-admin-desktop-windows-x64/core'),'--output',(Join-Path $validationRoot 'desktop-smoke'))}
    }
    $validationSuccess=$true
}catch{
    if($env:GITHUB_ACTIONS -eq 'true'){
        $detail=($_ | Out-String)+$_.ScriptStackTrace
        $detail=$detail.Replace('%','%25').Replace("`r",'%0D').Replace("`n",'%0A')
        Write-Output "::error title=Validation exception::$detail"
    }
    throw
}finally{
    $report=[pscustomobject]@{success=$validationSuccess;sourceRoot=$validationRepo;steps=$validationSteps;nativeTarget=$env:CARGO_TARGET_DIR}
    [IO.File]::WriteAllText((Join-Path $validationRoot 'validation.json'),($report|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Host "Validation evidence: $validationRoot"
    $env:CARGO_TARGET_DIR=$validationOldCargoTarget
    Pop-Location
}
