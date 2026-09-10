param(
    [Parameter(Mandatory = $true)]
    [string]$AssetPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$headers = @{ 'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36' }
$blockedHosts = '(?i)(baidu|bing|brave|sohu|zhihu|sogou|360|sulaixue|dakao8|kao900|gaoxiaoji|xsy-edu|zhila|wikipedia|douyin|bilibili|weixin|qq|csdn|toutiao|163|so\.com)$'

$catalog = Get-Content -LiteralPath $AssetPath -Raw | ConvertFrom-Json
$entries = @($catalog.entries | Where-Object { $_.sourceType -eq 'ziyan' -and -not $_.url })
$results = [System.Collections.Generic.List[object]]::new()

for ($index = 0; $index -lt $entries.Count; $index++) {
    $entry = $entries[$index]
    $query = [uri]::EscapeDataString('"' + $entry.name + '" 教务系统 登录 官方')
    $searchUrl = "https://search.brave.com/search?q=$query&source=web"
    $candidates = @()
    $errorText = ''
    try {
        $response = Invoke-WebRequest -Uri $searchUrl -UseBasicParsing -Headers $headers -TimeoutSec 25
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $candidates = @($response.Links | ForEach-Object {
            $href = [string]$_.href
            if ($href -notmatch '^https?://') { return }
            try { $uri = [uri]$href } catch { return }
            $candidateHost = $uri.Host.ToLowerInvariant()
            $baseHost = ($candidateHost -replace '^www\.', '')
            if (-not $candidateHost -or $baseHost -match $blockedHosts -or -not $seen.Add($href)) { return }
            $titleMatch = [regex]::Match([string]$_.outerHTML, '<div[^>]+class="[^"]*title[^"]*"[^>]+title="([^"]+)"', 'IgnoreCase')
            $title = if ($titleMatch.Success) { [System.Net.WebUtility]::HtmlDecode($titleMatch.Groups[1].Value) } else { '' }
            [pscustomobject]@{ url = $href; title = $title; host = $candidateHost }
        } | Select-Object -First 12)
    } catch {
        $errorText = $_.Exception.Message
    }

    $results.Add([pscustomobject]@{
        id = $entry.id
        name = $entry.name
        category = $entry.category
        referenceUrl = [string]$entry.referenceUrl
        candidates = $candidates
        searchError = $errorText
    })

    if (($index + 1) % 20 -eq 0 -or $index + 1 -eq $entries.Count) {
        Write-Output ("Searched {0}/{1}" -f ($index + 1), $entries.Count)
    }
    Start-Sleep -Milliseconds 900
}

$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
