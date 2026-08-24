function Analyze($path, $label) {
    $text = Get-Content -Path $path -Raw
    $blocks = [regex]::Matches($text, '(?m)^block \d+$')
    $blockCount = $blocks.Count
    
    $sections = [regex]::Split($text, '(?=^block \d+$)', 'Multiline')
    $notchCount = 0
    foreach ($s in $sections) { if ($s -match 'NOTCH_FILL') { $notchCount++ } }
    
    $score2Matches = [regex]::Matches($text, 'score2=([-\d.eE+]+)')
    $score2Sum = 0.0
    $score2Count = $score2Matches.Count
    foreach ($m in $score2Matches) { $score2Sum += [double]$m.Groups[1].Value }
    
    $multiCount = 0
    $multiDetails = @()
    foreach ($s in $sections) {
        $items = [regex]::Matches($s, '(?m)^\s+- id=')
        if ($items.Count -ge 2) {
            $multiCount++
            $blkMatch = [regex]::Match($s, '(?m)^block (\d+)$')
            $bn = if ($blkMatch.Success) { $blkMatch.Groups[1].Value } else { '?' }
            $ids = [regex]::Matches($s, '(?m)^\s+- id=(\d+)') | ForEach-Object { $_.Groups[1].Value }
            $pairStr = $ids -join ','
            $multiDetails += [PSCustomObject]@{ BlockNum = $bn; Ids = $pairStr }
        }
    }
    
    Write-Host "=== $label ==="
    Write-Host "  blockCount: $blockCount"
    Write-Host "  NOTCH_FILL blocks: $notchCount"
    Write-Host "  score2 count: $score2Count"
    Write-Host "  score2 sum: $score2Sum"
    Write-Host "  Multi-item blocks: $multiCount"
    return $multiDetails
}

$old = Analyze 'e:\code\NFPKSum\data\newOutputData\Cabinet3.txt' 'OLD'
$v2 = Analyze 'e:\code\NFPKSum\data\newOutputData2\Cabinet3.txt' 'V2'
$new = Analyze 'e:\code\NFPKSum\data\newOutputData3\Cabinet3.txt' 'NEW'

Write-Host ''
Write-Host '=== Multi-item pairing comparison (OLD vs NEW) ==='
if ($old.Count -ne $new.Count) {
    Write-Host "OLD has $($old.Count) multi-item blocks, NEW has $($new.Count) - COUNTS DIFFER"
    Write-Host 'OLD first 10:'
    for ($i=0; $i -lt [Math]::Min(10,$old.Count); $i++) {
        Write-Host "  block $($old[$i].BlockNum): [$($old[$i].Ids)]"
    }
    Write-Host 'NEW first 10:'
    for ($i=0; $i -lt [Math]::Min(10,$new.Count); $i++) {
        Write-Host "  block $($new[$i].BlockNum): [$($new[$i].Ids)]"
    }
} else {
    $diffs = @()
    for ($i=0; $i -lt $old.Count; $i++) {
        if ($old[$i].Ids -ne $new[$i].Ids) {
            $diffs += "#$i : OLD block $($old[$i].BlockNum)[$($old[$i].Ids)] -> NEW block $($new[$i].BlockNum)[$($new[$i].Ids)]"
        }
    }
    if ($diffs.Count -eq 0) {
        Write-Host 'All multi-item pairings are IDENTICAL between OLD and NEW.'
    } else {
        Write-Host "$($diffs.Count) of $($old.Count) multi-item blocks have CHANGED pairings:"
        $diffs | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" }
    }
}