param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [int]$StartIndex = 0,
    [int]$Count = 60,
    [int]$ThrottleLimit = 8
)

$ErrorActionPreference = 'Stop'
$entries = @(Get-Content -LiteralPath $InputPath -Raw | ConvertFrom-Json)
$selection = @($entries | Where-Object { $_.index -ge $StartIndex } | Select-Object -First $Count)

$results = $selection | ForEach-Object -Parallel {
    $entry = $_
    $links = [System.Collections.Generic.List[object]]::new()
    $errorText = ''
    $statusCode = 0
    $finalUrl = ''

    if ([string]::IsNullOrWhiteSpace([string]$entry.officialUrl)) {
        $errorText = 'No official homepage is listed.'
    } else {
        try {
            $headers = @{
                'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128 Safari/537.36'
                'Accept-Language' = 'zh-CN,zh;q=0.9'
            }
            $response = Invoke-WebRequest -Uri ([string]$entry.officialUrl) -UseBasicParsing -AllowInsecureRedirect -SkipCertificateCheck -MaximumRedirection 6 -TimeoutSec 20 -Headers $headers
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
                $reasons = [System.Collections.Generic.List[string]]::new()

                if ($label -match '教务系统|教务管理|教务平台|教务入口|学生入口|学生平台|信息门户|智慧校园|数字校园|统一身份|师生登录|校园门户|融合门户|融校园') {
                    $score += 12
                    $reasons.Add('anchor-login')
                } elseif ($label -match '教务处|教学管理|学生服务|网上办事|校内服务|快速通道') {
                    $score += 4
                    $reasons.Add('anchor-related')
                }

                if ($url -match '(?i)(^|[./_-])(jw|jwxt|jwgl|jwc|academic|student|portal|cas|authserver|ehall|e-hall|my|vpn)([./_:-]|$)') {
                    $score += 7
                    $reasons.Add('url-pattern')
                }
                if ($url -match '(?i)(login|slogin|default2?\.aspx|home\.aspx|jsxsd|jwweb|jwglxt|eams|lyuapServer)') {
                    $score += 6
                    $reasons.Add('login-pattern')
                }

                if ($score -gt 0 -and $seen.Add($url)) {
                    $links.Add([pscustomobject]@{
                        url = $url
                        text = $label
                        score = $score
                        reasons = @($reasons)
                    })
                }
            }
        } catch {
            $errorText = $_.Exception.Message
            if ($_.Exception.Response -and $_.Exception.Response.RequestMessage) {
                $finalUrl = [string]$_.Exception.Response.RequestMessage.RequestUri.AbsoluteUri
            }
        }
    }

    [pscustomobject]@{
        index = [int]$entry.index
        id = [string]$entry.id
        name = [string]$entry.name
        officialUrl = [string]$entry.officialUrl
        finalUrl = $finalUrl
        statusCode = $statusCode
        links = @($links | Sort-Object score -Descending | Select-Object -First 20)
        error = $errorText
    }
} -ThrottleLimit $ThrottleLimit

$results = @($results | Sort-Object index)
$results | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Crawled {0} homepages; {1} returned candidate links; {2} failed or had no homepage." -f $results.Count, @($results | Where-Object { $_.links.Count -gt 0 }).Count, @($results | Where-Object error).Count)
