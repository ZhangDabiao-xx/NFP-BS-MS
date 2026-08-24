$files = @{
    "OLD" = "e:\code\NFPKSum\data\newOutputData\Cabinet3.txt"
    "V2"  = "e:\code\NFPKSum\data\newOutputData2\Cabinet3.txt"
    "NEW" = "e:\code\NFPKSum\data\newOutputData3\Cabinet3.txt"
}

foreach ($label in $files.Keys) {
    $path = $files[$label]
    $text = Get-Content -Path $path -Raw
    
    # blockCount from header
    $bcMatch = [regex]::Match($text, '^blockCount=(\d+)')
    $bc = if ($bcMatch.Success) { $bcMatch.Groups[1].Value } else { "?" }
    
    # NOTCH_FILL blocks
    $sections = [regex]::Split($text, "`r`n(?=block \d+`r`n)", 'Multiline')
    $notchCount = 0
    foreach ($s in $sections) {
        if ($s -match 'NOTCH_FILL') { $notchCount++ }
    }
    
    # score2 sum
    $score2Matches = [regex]::Matches($text, 'score2=([-\d.eE+]+)')
    $score2Count = $score2Matches.Count
    $score2Sum = 0.0
    foreach ($m in $score2Matches) { $score2Sum += [double]$m.Groups[1].Value }
    
    # Multi-item blocks: count items using "- id="
    $blkSections = [regex]::Split($text, "`r`n(?=block \d+`r`n)", 'Multiline')
    $multiCount = 0
    $multiDetails = @()
    foreach ($s in $blkSections) {
        $itemMatches = [regex]::Matches($s, '  - id=(\d+)')
        if ($itemMatches.Count -ge 2) {
            $multiCount++
            $blkMatch = [regex]::Match($s, '^block (\d+)', 'Multiline')
            $bn = if ($blkMatch.Success) { $blkMatch.Groups[1].Value } else { '?' }
            $ids = @()
            foreach ($im in $itemMatches) { $ids += $im.Groups[1].Value }
            $multiDetails += [PSCustomObject]@{ BlockNum = $bn; Ids = $ids -join ',' }
        }
    }
    
    Write-Host "=== $label ==="
    Write-Host "  blockCount: $bc"
    Write-Host "  NOTCH_FILL blocks: $notchCount"
    Write-Host "  score2 count: $score2Count"
    Write-Host "  score2 sum: $score2Sum"
    Write-Host "  Multi-item blocks: $multiCount"
    if ($multiCount -gt 0 -and $multiCount -le 20) {
        foreach ($d in $multiDetails) {
            Write-Host "    block $($d.BlockNum): [$($d.Ids)]"
        }
    }
    Write-Host ""
}

# Now compare OLD vs NEW multi-item pairings
$oldText = Get-Content -Path $files["OLD"] -Raw
$newText = Get-Content -Path $files["NEW"] -Raw

$oldSections = [regex]::Split($oldText, "`r`n(?=block \d+`r`n)", 'Multiline')
$newSections = [regex]::Split($newText, "`r`n(?=block \d+`r`n)", 'Multiline')

$oldMulti = @()
foreach ($s in $oldSections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $bn = [regex]::Match($s, '^block (\d+)', 'Multiline').Groups[1].Value
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        $oldMulti += [PSCustomObject]@{ Block = $bn; Ids = $ids }
    }
}

$newMulti = @()
foreach ($s in $newSections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $bn = [regex]::Match($s, '^block (\d+)', 'Multiline').Groups[1].Value
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        $newMulti += [PSCustomObject]@{ Block = $bn; Ids = $ids }
    }
}

Write-Host "=== Multi-item comparison OLD vs NEW ==="
Write-Host "OLD multi-item blocks: $($oldMulti.Count)"
Write-Host "NEW multi-item blocks: $($newMulti.Count)"

if ($oldMulti.Count -ne $newMulti.Count) {
    Write-Host "Counts differ!"
} else {
    $diffCount = 0
    for ($i = 0; $i -lt $oldMulti.Count; $i++) {
        if ($oldMulti[$i].Ids -ne $newMulti[$i].Ids) {
            $diffCount++
            if ($diffCount -le 10) {
                Write-Host "  #$i : OLD[$($oldMulti[$i].Ids)] -> NEW[$($newMulti[$i].Ids)]"
            }
        }
    }
    if ($diffCount -eq 0) {
        Write-Host "All identical."
    } else {
        Write-Host "$diffCount of $($oldMulti.Count) blocks differ."
    }
}