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
$candidateLabels = '教务系统|教务管理系统|教务管理平台|教务平台|教务管理网|学生入口|统一身份认证|校园门户|信息门户|融合门户|融校园|智慧校园平台|数字校园平台'
$blockedHosts = '(?i)(^|\.)(baidu|bing|so|sogou|360|video)\.(com|cn|net)$'

$candidates = foreach ($record in $records) {
    foreach ($link in @($record.links)) {
        if ([int]$link.score -lt 12 -or [string]$link.text -notmatch $candidateLabels) { continue }
        try { $uri = [uri][string]$link.url } catch { continue }
        $candidateHost = $uri.Host.ToLowerInvariant()
        if (-not $candidateHost -or $candidateHost -match $blockedHosts) { continue }
        if ($candidateHost -match '^[0-9.]+$' -or $candidateHost -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)') { continue }

        [pscustomobject]@{
            index = [int]$record.index
            id = [string]$record.id
            name = [string]$record.name
            officialUrl = [string]$record.officialUrl
            sourceText = [string]$link.text
            sourceScore = [int]$link.score
            url = [string]$link.url
        }
    }
}

$results = $candidates | ForEach-Object -Parallel {
    $candidate = $_
    $statusCode = 0
    $finalUrl = ''
    $title = ''
    $contentLength = 0
    $profile = 'topology'
    $signals = [System.Collections.Generic.List[string]]::new()
    $errorText = ''

    try {
        $headers = @{
            'User-Agent' = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36'
            'Accept-Language' = 'zh-CN,zh;q=0.9'
        }
        $response = Invoke-WebRequest -Uri ([string]$candidate.url) -UseBasicParsing -AllowInsecureRedirect -SkipCertificateCheck -MaximumRedirection 8 -TimeoutSec 25 -Headers $headers
        $statusCode = [int]$response.StatusCode
        $finalUrl = [string]$response.BaseResponse.RequestMessage.RequestUri.AbsoluteUri
        $contentLength = [int]$response.Content.Length
        $titleMatch = [regex]::Match($response.Content, '<title[^>]*>(?<title>[\s\S]*?)</title>', 'IgnoreCase')
        $title = [System.Net.WebUtility]::HtmlDecode([regex]::Replace($titleMatch.Groups['title'].Value, '<[^>]+>', ' '))
        $title = ($title -replace '\s+', ' ').Trim()
        $haystack = ([string]$candidate.url + "`n" + $finalUrl + "`n" + $title + "`n" + $response.Content)

        if ($haystack -match '(?i)(jwglxt|xtgl/login_slogin|正方(软件|教务)|zftal)') {
            $profile = 'zhengfang'
            $signals.Add('zhengfang')
        } elseif ($haystack -match '(?i)(jsxsd|强智(科技|教务)|湖南强智)') {
            $profile = 'qiangzhi'
            $signals.Add('qiangzhi')
        } elseif ($haystack -match '(?i)(jwweb|青果(软件|教务)|kingosoft)') {
            $profile = 'kingosoft_new'
            $signals.Add('kingosoft')
        } elseif ($haystack -match '(?i)(/eams(?:/|\b)|EAMS教务)') {
            $profile = 'eams'
            $signals.Add('eams')
        } elseif ($haystack -match '(?i)(\.jw\.chaoxing\.com|超星教务)') {
            $profile = 'chaoxing'
            $signals.Add('chaoxing')
        }

        if ($haystack -match '(?i)(login|登录|统一身份|cas|authserver|lyuapServer|教务)') { $signals.Add('login-page') }
        if ($haystack -match '(?i)(课表|课程表|教学安排|我的课表)') { $signals.Add('timetable-copy') }
    } catch {
        $errorText = $_.Exception.Message
        if ($_.Exception.Response -and $_.Exception.Response.RequestMessage) {
            $finalUrl = [string]$_.Exception.Response.RequestMessage.RequestUri.AbsoluteUri
            try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
        }
    }

    [pscustomobject]@{
        index = [int]$candidate.index
        id = [string]$candidate.id
        name = [string]$candidate.name
        officialUrl = [string]$candidate.officialUrl
        sourceText = [string]$candidate.sourceText
        sourceScore = [int]$candidate.sourceScore
        url = [string]$candidate.url
        statusCode = $statusCode
        finalUrl = $finalUrl
        title = $title
        contentLength = $contentLength
        profile = $profile
        signals = @($signals)
        error = $errorText
    }
} -ThrottleLimit $ThrottleLimit

$results = @($results | Sort-Object index, @{ Expression = 'sourceScore'; Descending = $true })
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Probed {0} homepage-published candidates: {1} reached HTTP 2xx, {2} exposed a known platform fingerprint." -f $results.Count, @($results | Where-Object { $_.statusCode -ge 200 -and $_.statusCode -lt 300 }).Count, @($results | Where-Object { $_.profile -ne 'topology' }).Count)
