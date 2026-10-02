param(
    [string]$CredentialFile,
    [string]$Model,
    [string]$CaseFilter,
    [string]$ReportFile = 'live-results.json',
    [switch]$VerifyReport
)
$ErrorActionPreference = 'Stop'
$taskDirectory = $PSScriptRoot
$workspaceDirectory = Split-Path (Split-Path $taskDirectory -Parent) -Parent
$cacheDirectory = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$dependencyPaths = @('org.jetbrains.kotlin/kotlin-stdlib/1.9.10','com.google.code.gson/gson/2.10.1','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3')
$runtimeEntries = @((Join-Path $workspaceDirectory 'app/build/tmp/kotlin-classes/debug'))
foreach ($dependencyPath in $dependencyPaths) {
    $runtimeEntries += Get-ChildItem -LiteralPath (Join-Path $cacheDirectory "caches/modules-2/files-2.1/$dependencyPath") -Recurse -Filter '*.jar' | Select-Object -ExpandProperty FullName
}
$runtimeClasspath = $runtimeEntries -join ';'
& javac -encoding UTF-8 -cp $runtimeClasspath -d $taskDirectory (Join-Path $taskDirectory 'AssistantLiveEval.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$runtimeClasspath += ';' + $taskDirectory
if ($VerifyReport) {
    $reportPath = Join-Path $taskDirectory $ReportFile
    $reportDate = (Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json).date
    & java '-Duser.timezone=Asia/Hong_Kong' "-Dassistant.eval.date=$reportDate" -cp $runtimeClasspath AssistantLiveEval --verify-report $reportPath
    exit $LASTEXITCODE
}
if (!$CredentialFile) { throw 'Provide CredentialFile to run live evaluation.' }
$evalArguments = @('-Duser.timezone=Asia/Hong_Kong','-cp',$runtimeClasspath,'AssistantLiveEval',$CredentialFile,(Join-Path $taskDirectory $ReportFile))
if ($Model) { $evalArguments += $Model }
if ($CaseFilter) {
    if (!$Model) { throw 'Specify Model when filtering cases.' }
    $evalArguments += $CaseFilter
}
# Credentials stay in the JVM's memory; only the file path is a process argument.
& java @evalArguments
exit $LASTEXITCODE
