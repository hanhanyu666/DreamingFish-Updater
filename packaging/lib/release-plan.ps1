function Assert-ReleaseNotes {
    param([string]$NotesFile, [string]$AdminVersion, [string]$PlayerVersion)
    $text = Get-Content -LiteralPath $NotesFile -Raw -Encoding utf8
    $expected = "# [玩家 - $PlayerVersion][管理 - $AdminVersion] "
    if (-not $text.TrimStart([char]0xFEFF).StartsWith($expected, [StringComparison]::Ordinal)) {
        throw "Release notes must begin with $expected followed by a Chinese summary"
    }
    $guide = [regex]::Match($text, '(?ms)^## 更新教程[ \t]*\r?\n(?<body>.*?)(?=^## |\z)')
    if (-not $guide.Success) { throw 'Release notes must include a complete 更新教程 section for this release' }
    $sections = @('升级前备份', 'Windows 桌面安装版', 'Windows 桌面便携版', 'Windows / Linux 终端管理端', '玩家端是否需要更新', '升级后检查')
    foreach ($section in $sections) {
        $pattern = '(?ms)^### ' + [regex]::Escape($section) + '[ \t]*\r?\n(?<body>.*?)(?=^### |\z)'
        $part = [regex]::Match($guide.Groups['body'].Value, $pattern)
        if (-not $part.Success -or $part.Groups['body'].Value.Trim().Length -lt 25) {
            throw "Release update tutorial is missing substantive instructions: $section"
        }
    }
    foreach ($required in @('data', 'management-settings.json', 'management-web-auth.json', '签名', '外部')) {
        if (-not $guide.Groups['body'].Value.Contains($required)) {
            throw "Release update tutorial must explain preserving $required"
        }
    }
}

function Get-ReleasePlan {
    [CmdletBinding()]
    param([string]$Dist, [string]$NotesFile, [string]$Tag, [string]$Repository, [string]$AdminVersion, [string]$PlayerVersion)
    if ($Tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$') { throw 'Invalid release tag' }
    if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository' }
    if (-not (Test-Path -LiteralPath $NotesFile -PathType Leaf)) { throw "Release notes not found: $NotesFile" }
    Assert-ReleaseNotes -NotesFile $NotesFile -AdminVersion $AdminVersion -PlayerVersion $PlayerVersion
    $notes = (Resolve-Path -LiteralPath $NotesFile).Path
    $installer = "dfs-admin-desktop-windows-x64-$AdminVersion-setup.exe"
    $names = @("dfs-admin-desktop-windows-x64-$AdminVersion.zip", $installer, "dfs-admin-linux-x64-$AdminVersion.zip", "dfs-admin-windows-x64-$AdminVersion.zip", "dreamingfish-player-windows-x64-$PlayerVersion.zip")
    $assets = @($names | Where-Object { Test-Path -LiteralPath (Join-Path $Dist $_) -PathType Leaf } | ForEach-Object { (Resolve-Path -LiteralPath (Join-Path $Dist $_)).Path })
    if (-not $assets.Count) { throw "No release files for admin $AdminVersion / player $PlayerVersion in $Dist" }
    $latest = $null
    if ($assets | Where-Object { [IO.Path]::GetFileName($_) -eq $installer }) {
        $manifestPath = Join-Path $Dist 'latest.json'
        $signaturePath = Join-Path $Dist ($installer + '.sig')
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or -not (Test-Path -LiteralPath $signaturePath -PathType Leaf)) { throw 'A new desktop installer requires latest.json and its .sig artifact' }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
        $expected = "https://github.com/$Repository/releases/download/$Tag/$installer"
        $platform = $manifest.platforms.'windows-x86_64'
        if ($manifest.version -ne $AdminVersion -or $platform.url -ne $expected) { throw "latest.json must match desktop $AdminVersion and $expected" }
        $signature = (Get-Content -LiteralPath $signaturePath -Raw -Encoding utf8).Trim()
        if (-not $signature -or $platform.signature -ne $signature) { throw 'latest.json signature does not match the installer .sig artifact' }
        $latest = (Resolve-Path -LiteralPath $manifestPath).Path
    }
    [pscustomobject]@{ Tag=$Tag; Repository=$Repository; Notes=$notes; Assets=$assets; Latest=$latest; CarryLatest=($null -eq $latest) }
}
