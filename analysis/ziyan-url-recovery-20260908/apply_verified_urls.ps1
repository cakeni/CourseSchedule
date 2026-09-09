param(
    [string]$CatalogPath = ""
)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    $CatalogPath = Join-Path $repoRoot "app\src\main\assets\academic_school_directory.json"
}

$catalog = Get-Content -Raw -LiteralPath $CatalogPath | ConvertFrom-Json
$mappings = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "verified_urls.json") | ConvertFrom-Json
$seen = @{}
$applied = 0

foreach ($mapping in $mappings) {
    if ($seen.ContainsKey($mapping.id)) {
        throw "Duplicate mapping id: $($mapping.id)"
    }
    $seen[$mapping.id] = $true

    $matches = @($catalog.entries | Where-Object { $_.id -eq $mapping.id })
    if ($matches.Count -ne 1) {
        throw "Expected one catalog entry for $($mapping.id), got $($matches.Count)"
    }
    $entry = $matches[0]
    if ($entry.name -ne $mapping.name -or $entry.sourceType -ne "ziyan") {
        throw "Catalog identity mismatch for $($mapping.id)"
    }
    if ($mapping.enabled -eq $false) {
        $entry.profile = $null
        $entry.url = ""
        $entry.support = "adapter_required"
        $entry.cleartext = $false
        $entry.PSObject.Properties.Remove("authenticationUrls")
        continue
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$entry.url) -and $entry.url -ne $mapping.url) {
        throw "Refusing to replace an existing URL for $($mapping.id)"
    }

    $entry.profile = $mapping.profile
    $entry.url = $mapping.url
    $entry.support = "experimental"
    $entry.cleartext = $mapping.url.StartsWith("http://", [System.StringComparison]::OrdinalIgnoreCase)
    $entry.PSObject.Properties.Remove("authenticationUrls")
    [object[]]$authenticationUrls = if ($mapping.PSObject.Properties.Name -contains "authenticationUrls") {
        @($mapping.authenticationUrls | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    } else {
        @()
    }
    if ($authenticationUrls.Count -gt 0) {
        $entry | Add-Member -NotePropertyName authenticationUrls -NotePropertyValue ([object[]]$authenticationUrls)
    }
    $applied++
}

$json = $catalog | ConvertTo-Json -Depth 20 -Compress
[System.IO.File]::WriteAllText(
    (Resolve-Path -LiteralPath $CatalogPath).Path,
    $json,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "Applied $applied reviewed local-entry mappings; $($mappings.Count - $applied) rejected after review."
