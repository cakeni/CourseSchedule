param(
    [Parameter(Mandatory = $true)]
    [string]$InputDirectory,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [int]$ThrottleLimit = 8
)

$ErrorActionPreference = 'Stop'
$records = @(Get-ChildItem -LiteralPath $InputDirectory -Filter 'official_links_*.json' |
    Sort-Object Name |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })

$departments = foreach ($record in $records) {
    foreach ($link in @($record.links)) {
        if ([string]$link.text -notmatch '^(教务处|教务部|教学管理|教务管理|教学工作处|教务科研处|教科处|教务与科研处)(\s*\|.*)?$') { continue }
        try { $uri = [uri][string]$link.url } catch { continue }
        if ($uri.Scheme -notin @('http', 'https')) { continue }
        [pscustomobject]@{
            index = [int]$record.index
            id = [string]$record.id
            name = [string]$record.name
            pageUrl = [string]$link.url
            pageText = [string]$link.text
        }
    }
}

$results = $departments | ForEach-Object -Parallel {
    $source = $_
    $found = [System.Collections.Generic.List[object]]::new()
    $errorText = ''
    $statusCode = 0
    $finalUrl = ''
    try {
        $headers = @{
            'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128 Safari/537.36'
            'Accept-Language' = 'zh-CN,zh;q=0.9'
        }
        $response = Invoke-WebRequest -Uri ([string]$source.pageUrl) -UseBasicParsing -AllowInsecureRedirect -SkipCertificateCheck -MaximumRedirection 6 -TimeoutSec 22 -Headers $headers
        $statusCode = [int]$response.StatusCode
        $finalUrl = [string]$response.BaseResponse.RequestMessage.RequestUri.AbsoluteUri
        $baseUri = [uri]$finalUrl
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $anchors = [regex]::Matches($response.Content, '<a\b[^>]*href=["''](?<href>[^"'']+)["''][^>]*>(?<text>[\s\S]*?)</a>', 'IgnoreCase')
        foreach ($anchor in $anchors) {
            $rawHref = [System.Net.WebUtility]::HtmlDecode($anchor.Groups['href'].Value).Trim()
            if (-not $rawHref -or $rawHref -match '^(javascript:|mailto:|tel:|#)') { continue }
            try { $resolved = [uri]::new($baseUri, $rawHref) } catch { continue }
            if ($resolved.Scheme -notin @('http', 'https')) { continue }
            $label = [regex]::Replace($anchor.Groups['text'].Value, '<[^>]+>', ' ')
            $label = [System.Net.WebUtility]::HtmlDecode($label)
            $label = ($label -replace '\s+', ' ').Trim()
            $url = $resolved.AbsoluteUri
            $score = 0
            if ($label -match '教务系统|教务管理系统|教务平台|学生入口|信息门户|智慧校园|数字校园|统一身份|师生登录|校园门户|融合门户|融校园') { $score += 12 }
            if ($url -match '(?i)(^|[./_-])(jw|jwxt|jwgl|jwc|academic|student|portal|cas|authserver|ehall|e-hall|my)([./_:-]|$)') { $score += 7 }
            if ($url -match '(?i)(login|slogin|default2?\.aspx|home\.aspx|jsxsd|jwweb|jwglxt|eams|lyuapServer)') { $score += 6 }
            if ($score -ge 12 -and $seen.Add($url)) {
                $found.Add([pscustomobject]@{ url = $url; text = $label; score = $score })
            }
        }
    } catch {
        $errorText = $_.Exception.Message
        if ($_.Exception.Response -and $_.Exception.Response.RequestMessage) {
            $finalUrl = [string]$_.Exception.Response.RequestMessage.RequestUri.AbsoluteUri
        }
    }

    [pscustomobject]@{
        index = [int]$source.index
        id = [string]$source.id
        name = [string]$source.name
        pageText = [string]$source.pageText
        pageUrl = [string]$source.pageUrl
        finalUrl = $finalUrl
        statusCode = $statusCode
        links = @($found | Sort-Object score -Descending | Select-Object -First 20)
        error = $errorText
    }
} -ThrottleLimit $ThrottleLimit

$results = @($results | Sort-Object index, pageUrl)
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Crawled {0} teaching-office pages for {1} schools; {2} pages exposed candidate entry points." -f $results.Count, @($results | Select-Object -ExpandProperty index -Unique).Count, @($results | Where-Object { $_.links.Count -gt 0 }).Count)
