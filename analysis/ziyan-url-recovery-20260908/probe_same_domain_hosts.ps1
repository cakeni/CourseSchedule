param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [int]$ThrottleLimit = 20
)

$ErrorActionPreference = 'Stop'
$records = @(Get-Content -LiteralPath $InputPath -Raw | ConvertFrom-Json | Where-Object { -not $_.wildcardMatch })
$uniqueHosts = @($records | Group-Object host | ForEach-Object { $_.Group[0] })

$probes = $uniqueHosts | ForEach-Object -Parallel {
    $candidate = $_
    $statusCode = 0
    $requestedUrl = ''
    $finalUrl = ''
    $title = ''
    $contentLength = 0
    $profile = 'topology'
    $signals = [System.Collections.Generic.List[string]]::new()
    $errorText = ''

    foreach ($scheme in @('https', 'http')) {
        $requestedUrl = "${scheme}://$($candidate.host)/"
        try {
            $headers = @{
                'User-Agent' = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36'
                'Accept-Language' = 'zh-CN,zh;q=0.9'
            }
            $response = Invoke-WebRequest -Uri $requestedUrl -UseBasicParsing -AllowInsecureRedirect -SkipCertificateCheck -MaximumRedirection 8 -TimeoutSec 15 -Headers $headers
            $statusCode = [int]$response.StatusCode
            $finalUrl = [string]$response.BaseResponse.RequestMessage.RequestUri.AbsoluteUri
            $contentLength = [int]$response.Content.Length
            $titleMatch = [regex]::Match($response.Content, '<title[^>]*>(?<title>[\s\S]*?)</title>', 'IgnoreCase')
            $title = [System.Net.WebUtility]::HtmlDecode([regex]::Replace($titleMatch.Groups['title'].Value, '<[^>]+>', ' '))
            $title = ($title -replace '\s+', ' ').Trim()
            $haystack = ($requestedUrl + "`n" + $finalUrl + "`n" + $title + "`n" + $response.Content)
            if ($haystack -match '(?i)(jwglxt|xtgl/login_slogin|正方(软件|教务)|zftal)') {
                $profile = 'zhengfang'; $signals.Add('zhengfang')
            } elseif ($haystack -match '(?i)(jsxsd|强智(科技|教务)|湖南强智)') {
                $profile = 'qiangzhi'; $signals.Add('qiangzhi')
            } elseif ($haystack -match '(?i)(jwweb|青果(软件|教务)|kingosoft)') {
                $profile = 'kingosoft_new'; $signals.Add('kingosoft')
            } elseif ($haystack -match '(?i)(/eams(?:/|\b)|EAMS教务)') {
                $profile = 'eams'; $signals.Add('eams')
            } elseif ($haystack -match '(?i)(\.jw\.chaoxing\.com|超星教务)') {
                $profile = 'chaoxing'; $signals.Add('chaoxing')
            }
            if ($haystack -match '(?i)(login|登录|统一身份|cas|authserver|lyuapServer|教务)') { $signals.Add('login-page') }
            if ($haystack -match '(?i)(课表|课程表|教学安排|我的课表)') { $signals.Add('timetable-copy') }
            $errorText = ''
            break
        } catch {
            $errorText = $_.Exception.Message
            if ($_.Exception.Response -and $_.Exception.Response.RequestMessage) {
                $finalUrl = [string]$_.Exception.Response.RequestMessage.RequestUri.AbsoluteUri
                try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
            }
        }
    }

    [pscustomobject]@{
        host = [string]$candidate.host
        prefix = [string]$candidate.prefix
        requestedUrl = $requestedUrl
        statusCode = $statusCode
        finalUrl = $finalUrl
        title = $title
        contentLength = $contentLength
        profile = $profile
        signals = @($signals)
        error = $errorText
    }
} -ThrottleLimit $ThrottleLimit

$byHost = @{}
foreach ($probe in $probes) { $byHost[$probe.host] = $probe }
$result = foreach ($record in $records) {
    $probe = $byHost[[string]$record.host]
    [pscustomobject]@{
        index = [int]$record.index
        id = [string]$record.id
        name = [string]$record.name
        rootDomain = [string]$record.rootDomain
        prefix = [string]$record.prefix
        host = [string]$record.host
        addresses = @($record.addresses)
        requestedUrl = [string]$probe.requestedUrl
        statusCode = [int]$probe.statusCode
        finalUrl = [string]$probe.finalUrl
        title = [string]$probe.title
        contentLength = [int]$probe.contentLength
        profile = [string]$probe.profile
        signals = @($probe.signals)
        error = [string]$probe.error
    }
}

$result = @($result | Sort-Object index, prefix)
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Probed {0} non-wildcard hosts for {1} schools; {2} returned HTTP 2xx and {3} had login/platform signals." -f $probes.Count, @($result.index | Sort-Object -Unique).Count, @($probes | Where-Object { $_.statusCode -ge 200 -and $_.statusCode -lt 300 }).Count, @($probes | Where-Object { $_.signals.Count -gt 0 }).Count)
