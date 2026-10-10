<#
.SYNOPSIS
    Engineering consistency checks (stage 2 / T2.5).
.DESCRIPTION
    Read-only checks derived from .trae/documents/remediation-plan-2026-09-25.md.
    Prints a list of items still to fix; zero items means consistent.
    This file is intentionally ASCII-only: Windows PowerShell 5.x reads .ps1
    files as ANSI, so non-ASCII comments would corrupt parsing.
    Usage: powershell -ExecutionPolicy Bypass -File scripts\check-consistency.ps1
.NOTES
    Exit code 0 = all checks pass, 1 = items to fix, 2 = script error.
#>
[CmdletBinding()]
[CmdletBinding()]
param(
    # Project root. When omitted, resolved from this script's location, then cwd.
    [string]$Root = ''
)

# Resolve the project root outside the param block: $PSScriptRoot is not reliably
# populated while parameter defaults are bound (observed under powershell -File).
if ([string]::IsNullOrWhiteSpace($Root)) {
    if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $Root = (Split-Path -Parent $PSScriptRoot)
    } else {
        $Root = (Get-Location).Path
        if ((Split-Path -Leaf $Root) -eq 'scripts') { $Root = (Split-Path -Parent $Root) }
    }
}

$ErrorActionPreference = 'Stop'
$script:Fail = 0

function Write-Section([string]$Title) {
    Write-Output ''
    Write-Output "==== $Title ===="
}
function Write-Fail([string]$Message) {
    Write-Output "  [TODO] $Message"
    $script:Fail++
}
function Write-Ok([string]$Message) {
    Write-Output "  [ OK ] $Message"
}

$serverSrc = Join-Path $Root 'server\src\main\kotlin'
$coreSrc   = Join-Path $Root 'core\src\commonMain\kotlin'
$docsDir   = Join-Path $Root 'docs'

if (-not (Test-Path $serverSrc)) { throw "Server sources not found: $serverSrc" }

# ---------------------------------------------------------------- check 1
# Platform ids must be referenced via SourcePlatform, never hardcoded.
# Background: the StackExchange defect in T1.1 came from a literal
# "stackoverflow" that never matched the real id "stackexchange".
Write-Section '1. Hardcoded platform ids in source comparisons'
# Only inspect comparisons against ContentItem.source. Display strings such as
# tags = listOf("Wikipedia") and API parameters such as site="stackoverflow"
# are legitimate and must not be flagged.
$platformIds = @(
    'hackernews','stackexchange','stackoverflow','douban','arxiv','wikipedia',
    'bilibili','zhihu','xiaohongshu','douyin','wechat','quora','reddit',
    'twitter','youtube','cnki','nssd','caixin','substack','jstor-acm',
    'google-scholar','icourse163'
)
$alt = ($platformIds | ForEach-Object { [regex]::Escape($_) }) -join '|'
$sourceCmpPattern = '\.source\s*(==|!=|in)\s*"(' + $alt + ')"'
$hits1 = Get-ChildItem $serverSrc -Recurse -File -Filter *.kt |
    Select-String -Pattern $sourceCmpPattern |
    Where-Object { $_.Path -notmatch '\\test\\' }
if ($hits1) {
    foreach ($h in $hits1) {
        Write-Fail ("{0}:{1}  {2}" -f (Split-Path $h.Path -Leaf), $h.LineNumber, $h.Line.Trim())
    }
} else {
    Write-Ok 'No hardcoded platform ids in .source comparisons'
}

# ---------------------------------------------------------------- check 2
# Scope table (BROWSER_PRIMARY) vs actual adapter assembly form.
# Per platform-onboarding section 4: browser-primary platforms (assembled with
# primary = null) must be registered; HTTP-primary platforms must not be.
Write-Section '2. BROWSER_PRIMARY vs adapter assembly form'
$adaptersPath = Join-Path $serverSrc 'com\trae\info\server\crawler\Adapters.kt'
$bpPath       = Join-Path $serverSrc 'com\trae\info\server\service\BudgetPlanner.kt'

if ((Test-Path $adaptersPath) -and (Test-Path $bpPath)) {
    # Extract only the BROWSER_PRIMARY set body. A file-wide scan would also pick
    # up unrelated references elsewhere in the file (e.g. mediaKindOf), which
    # produced false positives.
    $bpLines = Get-Content $bpPath
    $bpStart = ($bpLines | Select-String -Pattern 'val\s+BROWSER_PRIMARY' | Select-Object -First 1).LineNumber
    if (-not $bpStart) { Write-Fail 'Could not locate the BROWSER_PRIMARY declaration'; }
    $bpBody = New-Object System.Collections.Generic.List[string]
    for ($i = $bpStart - 1; $i -lt $bpLines.Count; $i++) {
        $bpBody.Add($bpLines[$i])
        if ($i -gt ($bpStart - 1) -and $bpLines[$i] -match '^\s*\)\s*$') { break }
    }
    $declared = [regex]::Matches(($bpBody -join "`n"), 'SourcePlatform\.([A-Z_0-9]+)') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique


    # Walk the build() map body: track the current platform key and note whether
    # the following lines open a CompositeAdapter with primary = null.
    $lines = Get-Content $adaptersPath
    $browserPrimary = New-Object System.Collections.Generic.List[string]
    $current = $null
    foreach ($line in $lines) {
        $mKey = [regex]::Match($line, '^\s*SourcePlatform\.([A-Z_0-9]+)\s+to\s+')
        if ($mKey.Success) { $current = $mKey.Groups[1].Value; continue }
        if ($null -ne $current -and $line -match 'primary\s*=\s*null') {
            if (-not $browserPrimary.Contains($current)) { [void]$browserPrimary.Add($current) }
            $current = $null
        }
        # Close the entry once a nested call ends, to avoid cross-entry leakage.
        if ($null -ne $current -and $line -match '^\s*\)\s*,?\s*$') { $current = $null }
    }
    $browserPrimary = @($browserPrimary | Sort-Object -Unique)

    Write-Output ("  declared BROWSER_PRIMARY ({0}): {1}" -f $declared.Count, ($declared -join ', '))
    Write-Output ("  adapters with primary=null ({0}): {1}" -f $browserPrimary.Count, ($browserPrimary -join ', '))

    $missing = @($browserPrimary | Where-Object { $declared -notcontains $_ })
    $extra   = @($declared | Where-Object { $browserPrimary -notcontains $_ })
    foreach ($p in $missing) { Write-Fail "primary=null but not registered in BROWSER_PRIMARY: $p" }
    foreach ($p in $extra)   { Write-Fail "registered in BROWSER_PRIMARY but adapter is not primary=null: $p" }
    if ($missing.Count -eq 0 -and $extra.Count -eq 0) { Write-Ok 'Scope table matches adapter assembly' }
} else {
    Write-Fail 'Adapters.kt or BudgetPlanner.kt not found'
}

# ---------------------------------------------------------------- check 3
# isLowValue coverage: platforms whose adapter emits metadata-only summaries
# must be registered, otherwise a 12s/item AI summary is wasted.
Write-Section '3. isLowValue coverage for metadata-only sources'
$spsPath = Join-Path $serverSrc 'com\trae\info\server\service\ScrapingProcessService.kt'
if (Test-Path $spsPath) {
    $spsLines = Get-Content $spsPath
    $start = ($spsLines | Select-String -Pattern 'fun isLowValue\(' | Select-Object -First 1).LineNumber
    if ($start) {
        # Collect until the function's closing brace at the same indent level.
        $body = New-Object System.Collections.Generic.List[string]
        for ($i = $start; $i -lt $spsLines.Count; $i++) {
            $body.Add($spsLines[$i])
            if ($i -gt $start -and $spsLines[$i] -match '^\s{12}\}\s*$') { break }
        }
        $bodyText = $body -join "`n"
        $covered = [regex]::Matches($bodyText, 'SourcePlatform\.([A-Z_0-9]+)') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
        Write-Output ("  registered: {0}" -f ($covered -join ', '))
        $expected = @('TWITTER','BILIBILI','STACKEXCHANGE','DOUBAN')
        $gap = @($expected | Where-Object { $covered -notcontains $_ })
        foreach ($p in $gap) { Write-Fail "metadata-only source not registered in isLowValue: $p" }
        if ($gap.Count -eq 0) { Write-Ok 'All four metadata-only sources registered (X / Bilibili / StackExchange / Douban)' }
    } else {
        Write-Fail 'Could not locate the isLowValue function'
    }
} else {
    Write-Fail 'ScrapingProcessService.kt not found'
}

# ---------------------------------------------------------------- check 4
# Milestone identifiers: every referenced M0-M7 must have a single definition
# site (deliverable C12: docs/video-milestones.md).
Write-Section '4. Milestone references vs definitions'
$msFile = Join-Path $docsDir 'video-milestones.md'
$videoDocs = @('video-agent-watch.md','video-mcp-tool.md','video-translation-editing.md') |
    ForEach-Object { Join-Path $docsDir $_ } | Where-Object { Test-Path $_ }
$referenced = @()
foreach ($d in $videoDocs) {
    $referenced += [regex]::Matches((Get-Content $d -Raw), '\bM[0-7]\b') | ForEach-Object { $_.Value }
}
$referenced = @($referenced | Sort-Object -Unique)
Write-Output ("  referenced in the three direction docs: {0}" -f ($referenced -join ', '))

if (Test-Path $msFile) {
    $defined = [regex]::Matches((Get-Content $msFile -Raw), '\bM[0-7]\b') |
        ForEach-Object { $_.Value } | Sort-Object -Unique
    $undefined = @($referenced | Where-Object { $defined -notcontains $_ })
    foreach ($m in $undefined) { Write-Fail "referenced but undefined: $m" }
    if ($undefined.Count -eq 0) { Write-Ok 'All referenced milestone ids are defined' }
} else {
    Write-Fail 'docs/video-milestones.md not found (C12 pending; M1/M7 currently undefined)'
}

# ---------------------------------------------------------------- summary
Write-Section 'Summary'
if ($script:Fail -eq 0) {
    Write-Output '  All checks passed.'
    exit 0
} else {
    Write-Output ("  {0} item(s) to fix, listed above." -f $script:Fail)
    exit 1
}
