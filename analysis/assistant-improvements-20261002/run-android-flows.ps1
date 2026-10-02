param([string]$AdbPath = 'adb', [string]$Serial)
$ErrorActionPreference = 'Stop'
$workspaceDirectory = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$deviceArguments = if ($Serial) { @('-s', $Serial) } else { @() }
Push-Location $workspaceDirectory
try {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Test APK build failed.' }
    foreach ($apkPath in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        & $AdbPath @deviceArguments install -r -g $apkPath
        if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
    }
    function Invoke-Flow([string]$Classes, [string]$LogName) {
        $logPath = Join-Path $PSScriptRoot $LogName
        & $AdbPath @deviceArguments shell am instrument -w -r -e class $Classes com.courseschedule.test/androidx.test.runner.AndroidJUnitRunner *> $logPath
        if ($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath $logPath -Pattern 'OK \([1-9][0-9]* tests?\)' -Quiet)) {
            throw "Flow failed; inspect $logPath"
        }
        Get-Content -LiteralPath $logPath | Select-String 'OK \('
    }
    Invoke-Flow 'com.courseschedule.ui.assistant.AssistantConversationTest,com.courseschedule.ui.assistant.CourseAssistantFlowTest,com.courseschedule.ui.assistant.CourseAssistantManagementTest,com.courseschedule.ui.assistant.CourseAssistantReliabilityTest' 'instrumentation.log'
    Invoke-Flow 'com.courseschedule.ui.assistant.AssistantProcessRestartTest#prepareBeforeProcessKill' 'restart-prepare.log'
    & $AdbPath @deviceArguments shell am force-stop com.courseschedule
    Invoke-Flow 'com.courseschedule.ui.assistant.AssistantProcessRestartTest#verifyAfterProcessKill' 'restart-verify.log'
} finally { Pop-Location }
