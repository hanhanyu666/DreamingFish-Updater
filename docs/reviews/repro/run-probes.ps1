param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$reviewRepository = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
Push-Location -LiteralPath $reviewRepository
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd -o test
        if ($LASTEXITCODE -ne 0) { throw 'Build or baseline tests failed.' }
    }
    $reviewOutput = Join-Path $reviewRepository ('target\architecture-review-2026-10-01\run-' + [guid]::NewGuid())
    $reviewOutput = [IO.Path]::GetFullPath($reviewOutput)
    if (-not $reviewOutput.StartsWith($reviewRepository + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Probe output is outside the repository.'
    }
    $reviewClasses = Join-Path $reviewOutput 'classes'
    New-Item -ItemType Directory -Path $reviewClasses | Out-Null
    $reviewClassPaths = foreach ($reviewModule in @('management-core', 'player-app', 'update-engine')) {
        $reviewReport = Get-ChildItem -LiteralPath (Join-Path $reviewRepository "$reviewModule\target\surefire-reports") -Filter 'TEST-*.xml' | Select-Object -First 1
        if ($null -eq $reviewReport) { throw "No test classpath found for $reviewModule." }
        [xml]$reviewXml = Get-Content -LiteralPath $reviewReport.FullName -Raw
        ($reviewXml.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
    }
    $reviewClasspath = ($reviewClassPaths -join ';').Split(';') | Select-Object -Unique
    $reviewClasspath = $reviewClasspath -join ';'
    $reviewSources = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*AuditProbe.java' | ForEach-Object { $_.FullName }
    & javac '-J-Duser.language=en' -encoding UTF-8 -cp $reviewClasspath -d $reviewClasses $reviewSources
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed.' }
    $reviewClasspath = $reviewClasses + ';' + $reviewClasspath
    $reviewJunctionRoot = Join-Path $reviewOutput 'junction'
    $reviewInstance = Join-Path $reviewJunctionRoot 'instance'
    $reviewOutside = Join-Path $reviewJunctionRoot 'outside'
    New-Item -ItemType Directory -Path $reviewInstance | Out-Null
    New-Item -ItemType Directory -Path $reviewOutside | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $reviewInstance 'mods') -Target $reviewOutside | Out-Null
    foreach ($reviewProbe in @('cn.dreamingfish.updater.management.ManagementAuditProbe', 'cn.dreamingfish.updater.player.PlayerAuditProbe', 'cn.dreamingfish.updater.engine.EngineAuditProbe')) {
        & java -cp $reviewClasspath $reviewProbe $reviewOutput $reviewInstance | Tee-Object -FilePath (Join-Path $reviewOutput 'observations.txt') -Append
        if ($LASTEXITCODE -ne 0) { throw "Probe execution failed: $reviewProbe" }
    }
    Write-Output "Probe artifacts: $reviewOutput"
} finally { Pop-Location }
