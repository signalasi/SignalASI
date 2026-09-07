param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][long]$Source,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$DesktopJournal = "$env:APPDATA\GalaxySSI\diagnostics\agent_latency_v1.jsonl"
)

$ErrorActionPreference = 'Stop'
if ($Source -le 0) { throw 'Pass the source from a completed live-final recovery test' }
$caseId = "live-final-$Source"
if ($caseId -notmatch '^live-final-[0-9]{10,20}$') { throw 'Invalid live-final case ID' }
$model = (& $Adb -s $Serial shell getprop ro.product.model | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'SM-G9880') { throw 'Only S20U SM-G9880 is authorized' }
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $traceId = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("$caseId-task")))).Replace('-', '').ToLowerInvariant()
} finally { $sha.Dispose() }

function Read-Points([string[]]$Lines, [string]$Side) {
    foreach ($line in $Lines) {
        try { $point = $line | ConvertFrom-Json } catch { continue }
        if ($point.trace_id -cne $traceId -or $point.clock_id -cnotmatch '^[a-f0-9]{32}$' -or
            $point.operation_id -cnotmatch '^[a-f0-9]{64}$' -or
            $point.stage -cnotmatch "^${Side}_recovery_(query|page|body|checkpoint|lookup|restore|publish)_(started|finished)$" -or
            $point.outcome -cnotmatch '^(|completed|failed|cancelled|timed_out)$') { continue }
        if ($point.monotonic_ns -isnot [long] -and $point.monotonic_ns -isnot [int]) { continue }
        if ($point.monotonic_ns -lt 0) { continue }
        $point
    }
}

function Measure-Points([object[]]$Points) {
    $grouped = $Points | Group-Object { "$($_.clock_id)|$($_.operation_id)|$($_.stage -creplace '_(started|finished)$','')" }
    foreach ($group in $grouped) {
        $start = $group.Group | Where-Object { $_.stage.EndsWith('_started') } |
            Sort-Object monotonic_ns | Select-Object -First 1
        if ($null -eq $start) { continue }
        $end = $group.Group | Where-Object { $_.stage.EndsWith('_finished') -and $_.monotonic_ns -ge $start.monotonic_ns } |
            Sort-Object monotonic_ns | Select-Object -First 1
        [pscustomobject]@{
            stage = $start.stage -creplace '_started$', ''
            clock_id = $start.clock_id
            operation_id = $start.operation_id
            started_ns = $start.monotonic_ns
            finished_ns = $(if ($null -eq $end) { $null } else { $end.monotonic_ns })
            outcome = $(if ($null -eq $end) { 'incomplete' } else { $end.outcome })
            duration_ms = $(if ($null -eq $end) { $null } else { [Math]::Round(($end.monotonic_ns - $start.monotonic_ns) / 1000000.0, 3) })
        }
    }
}

$phone = & $Adb -s $Serial exec-out run-as com.galaxyssi.chat cat no_backup/diagnostics/agent_latency_v1.jsonl
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the diagnostic journal; use the authorized Debug validation build before restoring Release' }
if (-not (Test-Path -LiteralPath $DesktopJournal -PathType Leaf)) { throw 'Desktop diagnostic journal not found' }
$phonePoints = @(Read-Points $phone 'phone')
$desktopPoints = @(Read-Points (Get-Content -LiteralPath $DesktopJournal) 'desktop')
$phoneSamples = @(Measure-Points $phonePoints)
$desktopSamples = @(Measure-Points $desktopPoints)
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$output = Join-Path $root "build\$caseId"
New-Item -ItemType Directory -Force -Path $output | Out-Null
[ordered]@{
    case_id = $caseId
    trace_id = $traceId
    scope = 'one live final-result recovery case; no cross-device clock subtraction'
    limitations = 'Current bounded journals only; dropped or rotated points can be absent. Child spans overlap body duration. Publish-call completion does not prove receipt or UI display. Not a representative latency percentile.'
    phone = $phoneSamples
    desktop = $desktopSamples
} | ConvertTo-Json -Depth 5 | Tee-Object -FilePath (Join-Path $output 'stage-timings.json')
if ($phoneSamples.Count -eq 0 -or $desktopSamples.Count -eq 0) {
    throw 'Missing recovery samples on at least one device; report retained, do not infer zero latency'
}
