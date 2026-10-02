#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $SigningConfig,
    [Parameter(Mandatory)][string] $UnsignedHap,
    [Parameter(Mandatory)][string] $OutputHap
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'DPAPI 发布签名脚本仅支持 Windows。' }
$configPath = (Resolve-Path -LiteralPath $SigningConfig).Path
try { $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json }
catch { throw '无法读取本地发布签名配置。' }
$configDirectory = Split-Path -Parent $configPath
foreach ($field in @('storeFile', 'passwordFile', 'certpath', 'profile', 'javaHome', 'signToolJar')) {
    $value = [string] $config.$field
    if (-not $value) { throw "发布签名缺少字段：$field。" }
    $materialPath = if ([System.IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $configDirectory $value }
    $config.$field = (Resolve-Path -LiteralPath $materialPath).Path
}
if (-not $config.keyAlias -or -not $config.bundleName) { throw '发布签名缺少 keyAlias 或 bundleName。' }
$javaPath = Join-Path $config.javaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaPath)) { throw '未找到 JDK java.exe。' }
$inputPath = (Resolve-Path -LiteralPath $UnsignedHap).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputHap)
if ($inputPath -eq $outputPath) { throw '签名输出必须与未签名输入分开。' }
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$verificationDirectory = Join-Path $outputDirectory 'signing-verification'
New-Item -ItemType Directory -Path $verificationDirectory -Force | Out-Null
$profileResultPath = Join-Path $verificationDirectory 'source-profile.json'
$profileLog = & $javaPath -jar $config.signToolJar verify-profile -inFile $config.profile -outFile $profileResultPath 2>&1
if ($LASTEXITCODE -ne 0) { throw '官方发布 Profile 校验失败。' }
$profileResult = Get-Content -LiteralPath $profileResultPath -Raw | ConvertFrom-Json
$profileContent = if ($profileResult.content -is [string]) { $profileResult.content | ConvertFrom-Json } else { $profileResult.content }
if (-not $profileResult.verifiedPassed -or $profileContent.type -ne 'release' -or
    $profileContent.'bundle-info'.'bundle-name' -ne $config.bundleName) { throw '发布 Profile 类型或包名不匹配。' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$hapZip = [System.IO.Compression.ZipFile]::OpenRead($inputPath)
try {
    $manifestEntry = $hapZip.GetEntry('module.json')
    if ($null -eq $manifestEntry) { throw '输入 HAP 缺少 module.json。' }
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.app.bundleName -ne $config.bundleName) { throw 'HAP 与发布 Profile 包名不匹配。' }
    $compatibleVersion = ([int] $manifest.app.minAPIVersion % 1000).ToString()
} finally { $hapZip.Dispose() }

try { $securePassword = (Get-Content -LiteralPath $config.passwordFile -Raw).Trim() | ConvertTo-SecureString }
catch { throw '无法解密本机签名密码，请使用创建该 DPAPI 文件的 Windows 用户。' }
$plainPassword = $null
$pendingPath = Join-Path $outputDirectory ('.signing-' + [guid]::NewGuid().ToString('N') + '.hap')
try {
$process = [System.Diagnostics.Process]::new()
try {
    $plainPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
    $process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new($javaPath)
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in @('-cp', $config.signToolJar, (Join-Path $PSScriptRoot 'ReleaseSigner.java'),
        $config.keyAlias, $config.storeFile, $config.certpath, $config.profile, $inputPath, $pendingPath, $compatibleVersion)) {
        $process.StartInfo.ArgumentList.Add([string] $argument)
    }
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.WriteLine($plainPassword)
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(60000)) { $process.Kill(); throw '发布签名超时。' }
    $safeOutput = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Replace($plainPassword, '<redacted>')
    if ($process.ExitCode -ne 0) { throw "发布签名失败：$safeOutput" }
} finally {
    $plainPassword = $null
    if ($null -ne $securePassword) { $securePassword.Dispose() }
    $process.Dispose()
}
if (-not (Test-Path -LiteralPath $pendingPath) -or (Get-Item -LiteralPath $pendingPath).Length -eq 0) { throw '未生成发布签名 HAP。' }
$verificationLog = & $javaPath -jar $config.signToolJar verify-app -inFile $pendingPath -outCertChain (Join-Path $verificationDirectory 'embedded-chain.cer') -outProfile (Join-Path $verificationDirectory 'embedded-profile.p7b') 2>&1
if ($LASTEXITCODE -ne 0) { throw '官方签名包校验失败。' }
Move-Item -LiteralPath $pendingPath -Destination $outputPath -Force
Get-FileHash -LiteralPath $outputPath -Algorithm SHA256 | Select-Object Algorithm, Hash, Path
} finally {
    if (Test-Path -LiteralPath $pendingPath) { Remove-Item -LiteralPath $pendingPath -Force }
}
