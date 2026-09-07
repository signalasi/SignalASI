param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [long]$Source = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(),
    [ValidateSet('all', 'setup', 'inbox', 'ui', 'cold')][string]$Phase = 'all',
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$caseId = "live-final-$Source"
if ($Source -le 0 -or $caseId -notmatch '^live-final-[0-9]{10,20}$') { throw 'Invalid test source' }
if (-not (Test-Path -LiteralPath $Adb)) { throw 'Pass -Adb with the installed Android platform-tools path' }
$model = (& $Adb -s $Serial shell getprop ro.product.model | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'SM-G9880') { throw 'This acceptance run is authorized only on S20U SM-G9880' }
$output = Join-Path $root "build\$caseId"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$phases = [ordered]@{
    setup = 'submitAndDropOnlyTestFinal'
    inbox = 'restartAutomaticallyFetchesArchivedBody'
    ui = 'coldUiConsumesExactlyOneRecoveredReply'
    cold = 'subsequentColdStartKeepsExactlyOneReply'
}
foreach ($entry in $phases.GetEnumerator()) {
    if ($Phase -ne 'all' -and $Phase -ne $entry.Key) { continue }
    Write-Output "Case $caseId phase=$($entry.Key); force-stopping the target App, not clearing its data."
    & $Adb -s $Serial shell am force-stop com.galaxyssi.chat
    if ($LASTEXITCODE -ne 0) { throw 'Failed to force-stop target App' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $result = & $Adb -s $Serial shell am instrument -w -r `
        -e class "com.galaxyssi.chat.AgentLiveFinalRecoveryDeviceTest#$($entry.Value)" `
        -e live_final_probe true -e live_final_id $caseId -e live_final_source "$Source" `
        com.galaxyssi.chat.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $code = $LASTEXITCODE
    $watch.Stop()
    $result | Tee-Object -FilePath (Join-Path $output "$($entry.Key).log")
    & $Adb -s $Serial logcat -d -t 4000 -s 'System.out:I' '*:S' |
        Where-Object { $_.Contains('LIVE_FINAL phase=') -and $_.Contains("case=$caseId ") } |
        Tee-Object -FilePath (Join-Path $output 'metrics.log')
    $passed = $code -eq 0 -and ($result -join "`n") -match 'OK \(1 test\)'
    Write-Output "Phase=$($entry.Key) elapsed_ms=$($watch.ElapsedMilliseconds) passed=$passed"
    if (-not $passed) {
        throw "Phase $($entry.Key) failed. Evidence retained in $output. Diagnose before resuming with -Source $Source -Phase $($entry.Key); never resubmit an existing setup."
    }
}
Write-Output "Completed selected phases. Evidence: $output. Restore the non-debug Release APK after testing."
