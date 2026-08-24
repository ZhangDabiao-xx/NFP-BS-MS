$newText = Get-Content -Path "e:\code\NFPKSum\data\newOutputData3\Cabinet3.txt" -Raw
$newSections = [regex]::Split($newText, "`r`n(?=block \d+`r`n)", 'Multiline')

Write-Host "=== NEW multi-item blocks ==="
$n = 0
foreach ($s in $newSections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $n++
        $bn = [regex]::Match($s, '^block (\d+)', 'Multiline').Groups[1].Value
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        Write-Host "  block $bn : [$ids]"
    }
}
Write-Host "Total: $n"

$v2Text = Get-Content -Path "e:\code\NFPKSum\data\newOutputData2\Cabinet3.txt" -Raw
$v2Sections = [regex]::Split($v2Text, "`r`n(?=block \d+`r`n)", 'Multiline')

Write-Host ""
Write-Host "=== V2 multi-item blocks ==="
$n2 = 0
foreach ($s in $v2Sections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $n2++
        $bn = [regex]::Match($s, '^block (\d+)', 'Multiline').Groups[1].Value
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        Write-Host "  block $bn : [$ids]"
    }
}
Write-Host "Total: $n2"

# Cross-compare OLD vs NEW
Write-Host ""
Write-Host "=== Cross-comparison OLD vs NEW ==="
$oldText = Get-Content -Path "e:\code\NFPKSum\data\newOutputData\Cabinet3.txt" -Raw
$oldSections = [regex]::Split($oldText, "`r`n(?=block \d+`r`n)", 'Multiline')
$oldPairs = @{}
foreach ($s in $oldSections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        $oldPairs[$ids] = $true
    }
}
$newPairs = @{}
foreach ($s in $newSections) {
    $ims = [regex]::Matches($s, '  - id=(\d+)')
    if ($ims.Count -ge 2) {
        $ids = ($ims | ForEach-Object { $_.Groups[1].Value }) -join ','
        $newPairs[$ids] = $true
    }
}

$onlyOld = @()
foreach ($k in $oldPairs.Keys) { if (-not $newPairs.ContainsKey($k)) { $onlyOld += $k } }
$onlyNew = @()
foreach ($k in $newPairs.Keys) { if (-not $oldPairs.ContainsKey($k)) { $onlyNew += $k } }
$common = @()
foreach ($k in $oldPairs.Keys) { if ($newPairs.ContainsKey($k)) { $common += $k } }

Write-Host "Common pairings: $($common.Count)"
Write-Host "Only in OLD (removed in NEW): $($onlyOld.Count)"
foreach ($p in $onlyOld) { Write-Host "  [$p]" }
Write-Host "Only in NEW (added): $($onlyNew.Count)"
foreach ($p in $onlyNew) { Write-Host "  [$p]" }