param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [int]$ThrottleLimit = 64
)

$ErrorActionPreference = 'Stop'
$entries = @(Get-Content -LiteralPath $InputPath -Raw | ConvertFrom-Json | Where-Object officialUrl)
$prefixes = @(
    'jw', 'jwxt', 'jwgl', 'jwc', 'portal', 'cas', 'authserver', 'ehall', 'my', 'ids',
    'sso', 'uap', 'tyrz', 'i', 'one', 'smart', 'zhxy', 'jwapp', 'jiaowu', 'jwnew',
    'xk', 'xkxt', 'dean', 'student', 'service', 'urp',
    'jw1', 'jw2', 'jw3', 'jwg', 'jwmis', 'jws', 'jwweb', 'jwglxt', 'jwcxt',
    'jwa', 'jwb', 'edu', 'eams', 'xjw', 'xjwgl', 'xjwxt', 'xsjw', 'xsjwxt',
    'zfjw', 'zf', 'jxgl', 'jxt', 'jxjw', 'jwmh', 'hall', 'bsdt', 'campus',
    'digital', 'center', 'web', 'login', 'identity', 'id', 'auth', 'oauth',
    'graduate', 'yjs', 'yjsy', 'yjsgl', 'post', 'gmis', 'webvpn'
)

function Get-RootDomain([string]$hostName) {
    $labels = @($hostName.Trim('.').ToLowerInvariant().Split('.'))
    if ($labels.Count -lt 2) { return $hostName }
    if ($labels.Count -ge 3 -and ($labels[-2] + '.' + $labels[-1]) -in @('edu.cn', 'com.cn', 'net.cn', 'org.cn')) {
        return ($labels[-3..-1] -join '.')
    }
    return ($labels[-2..-1] -join '.')
}

$schools = foreach ($entry in $entries) {
    try { $homepageUri = [uri][string]$entry.officialUrl } catch { continue }
    if (-not $homepageUri.Host) { continue }
    [pscustomobject]@{
        index = [int]$entry.index
        id = [string]$entry.id
        name = [string]$entry.name
        rootDomain = Get-RootDomain $homepageUri.Host
    }
}

$roots = @($schools.rootDomain | Sort-Object -Unique)
$hostTasks = foreach ($root in $roots) {
    [pscustomobject]@{ rootDomain = $root; prefix = '_wildcard'; host = "no-such-courseschedule-probe.$root" }
    foreach ($prefix in $prefixes) {
        [pscustomobject]@{ rootDomain = $root; prefix = $prefix; host = "$prefix.$root" }
    }
}

$resolved = $hostTasks | ForEach-Object -Parallel {
    $task = $_
    $addresses = @()
    $errorText = ''
    try {
        $answer = [System.Net.Dns]::GetHostAddressesAsync([string]$task.host).WaitAsync([TimeSpan]::FromSeconds(4)).Result
        $addresses = @($answer.IPAddressToString | Sort-Object -Unique)
    } catch {
        $errorText = $_.Exception.GetType().Name
    }
    [pscustomobject]@{
        rootDomain = [string]$task.rootDomain
        prefix = [string]$task.prefix
        host = [string]$task.host
        addresses = $addresses
        error = $errorText
    }
} -ThrottleLimit $ThrottleLimit

$wildcards = @{}
foreach ($row in @($resolved | Where-Object prefix -eq '_wildcard')) {
    $wildcards[$row.rootDomain] = @($row.addresses) -join ','
}

$result = foreach ($row in @($resolved | Where-Object prefix -ne '_wildcard')) {
    if ($row.addresses.Count -eq 0) { continue }
    $addressKey = @($row.addresses) -join ','
    foreach ($school in @($schools | Where-Object rootDomain -eq $row.rootDomain)) {
        [pscustomobject]@{
            index = [int]$school.index
            id = [string]$school.id
            name = [string]$school.name
            rootDomain = [string]$row.rootDomain
            prefix = [string]$row.prefix
            host = [string]$row.host
            addresses = @($row.addresses)
            wildcardMatch = ($wildcards[$row.rootDomain] -and $wildcards[$row.rootDomain] -eq $addressKey)
        }
    }
}

$result = @($result | Sort-Object index, prefix)
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ("Resolved {0} common subdomains for {1} schools; {2} differ from each root's wildcard answer." -f $result.Count, @($result.index | Sort-Object -Unique).Count, @($result | Where-Object { -not $_.wildcardMatch }).Count)
