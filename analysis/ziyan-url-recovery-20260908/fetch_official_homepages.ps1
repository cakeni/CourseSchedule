param(
    [Parameter(Mandatory = $true)]
    [string]$AssetPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$dataset = 'RenzhiMind%2FEduCN'
$headers = @{ 'User-Agent' = 'CourseSchedule-directory-research/1.0' }
$rows = [System.Collections.Generic.List[object]]::new()

foreach ($split in @(
    @{ Name = 'general'; Count = 2919 },
    @{ Name = 'adult'; Count = 248 }
)) {
    for ($offset = 0; $offset -lt $split.Count; $offset += 100) {
        $apiUrl = "https://datasets-server.huggingface.co/rows?dataset=$dataset&config=default&split=$($split.Name)&offset=$offset&length=100"
        $response = Invoke-RestMethod -Uri $apiUrl -Headers $headers -TimeoutSec 45
        foreach ($item in $response.rows) {
            $rows.Add($item.row)
        }
    }
}

$byName = @{}
foreach ($row in $rows) {
    $byName[[string]$row.'学校名称'] = $row
}

$aliases = @{
    '东北石油大学 - 研究生' = '东北石油大学'
    '甘肃警察职业学院' = '甘肃警察学院'
    '邢台医学院（原邢台医学高等专科学校）' = '邢台医学院'
}

$catalog = Get-Content -LiteralPath $AssetPath -Raw | ConvertFrom-Json
$entries = @($catalog.entries | Where-Object { $_.sourceType -eq 'ziyan' -and -not $_.url })
$result = for ($index = 0; $index -lt $entries.Count; $index++) {
    $entry = $entries[$index]
    $datasetName = if ($aliases.ContainsKey([string]$entry.name)) {
        $aliases[[string]$entry.name]
    } else {
        [string]$entry.name
    }
    $row = $byName[$datasetName]
    $officialUrl = if ($null -ne $row -and [string]$row.'学校官网' -ne '-') {
        [string]$row.'学校官网'
    } else {
        ''
    }

    [pscustomobject]@{
        index = $index
        id = [string]$entry.id
        name = [string]$entry.name
        category = [string]$entry.category
        datasetName = $datasetName
        schoolCode = if ($null -ne $row) { [string]$row.'学校标识码' } else { '' }
        officialUrl = $officialUrl
        referenceUrl = [string]$entry.referenceUrl
    }
}

$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Matched {0}/{1} entries to the official-homepage dataset ({2} usable URLs)." -f @($result | Where-Object schoolCode).Count, $entries.Count, @($result | Where-Object officialUrl).Count)
