# WTM smoke test: panel -> login -> command -> backup -> restore -> stop.
# ASCII-only on purpose (Windows PowerShell 5.1 without BOM).
param([string]$Root = '')

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path) }

$java  = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) { $java = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot\bin\java.exe' }
$smoke = Join-Path $Root 'smoke'
$base  = 'http://127.0.0.1:18080'
$fail  = 0

function Step($name) { Write-Host ""; Write-Host "== $name" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "  PASS  $msg" -ForegroundColor Green }
function No($msg)   { Write-Host "  FAIL  $msg" -ForegroundColor Red; $script:fail++ }

Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue

Step 'prepare fixture'
$fixtureConfig = Join-Path $smoke 'config.yaml'
if (-not (Test-Path -LiteralPath $fixtureConfig)) {
    New-Item -ItemType Directory -Force -Path `
        (Join-Path $smoke 'server\world\region'), `
        (Join-Path $smoke 'server\world\playerdata'), `
        (Join-Path $smoke 'data') | Out-Null

    # 8KB 全零 region 头部 + 尾标记：头部合法、无区块，体检不会报错
    $mca = New-Object byte[] 8199
    [System.Text.Encoding]::ASCII.GetBytes('CHUNK-A').CopyTo($mca, 8192)
    [System.IO.File]::WriteAllBytes((Join-Path $smoke 'server\world\region\r.0.0.mca'), $mca)

    # level.dat：真正的 gzip 载荷
    $ms = New-Object System.IO.MemoryStream
    $gz = New-Object System.IO.Compression.GZipStream($ms, [System.IO.Compression.CompressionMode]::Compress)
    $payload = [System.Text.Encoding]::ASCII.GetBytes('LEVEL-DATA')
    $gz.Write($payload, 0, $payload.Length)
    $gz.Dispose()
    [System.IO.File]::WriteAllBytes((Join-Path $smoke 'server\world\level.dat'), $ms.ToArray())
    $ms.Dispose()
    [System.IO.File]::WriteAllText((Join-Path $smoke 'server\world\playerdata\abc.dat'), 'PLAYER')

    $hash = (& $java -cp (Join-Path $Root 'build\wtm.jar') wtm.Main hash-password test1234 |
        Select-Object -Last 1).Trim()
    $classes = Join-Path $Root 'build\classes'
    $cfg = @"
server:
  command: "$java"
  args:
    - -cp
    - $classes
    - wtm.test.MockServer
  dir: $smoke/server
  autostart: true
  ready_timeout: 60s
  stop_timeout: 20s

panel:
  listen: 127.0.0.1
  port: 18080
  password_hash: "$hash"

backup:
  dir: $smoke/data
  worlds: [world]
  at: "04:00"
  keep_last: 5
  keep_daily: 3
  wait_idle_minutes: 1
"@
    [System.IO.File]::WriteAllText($fixtureConfig, $cfg, (New-Object System.Text.UTF8Encoding($false)))
    Ok 'fixture created'
} else {
    Ok 'fixture reused'
}
Remove-Item -LiteralPath (Join-Path $smoke 'data') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $smoke 'data') | Out-Null

Step 'start wtm'
$proc = Start-Process -FilePath $java `
    -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', (Join-Path $Root 'build\wtm.jar'),
                    'run', (Join-Path $smoke 'config.yaml')) `
    -WorkingDirectory $smoke -PassThru `
    -RedirectStandardOutput (Join-Path $smoke 'wtm.out') `
    -RedirectStandardError  (Join-Path $smoke 'wtm.err')

$up = $false
for ($i = 0; $i -lt 80; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "$base/" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $up = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) {
    No 'panel did not start'
    Get-Content (Join-Path $smoke 'wtm.err') -ErrorAction SilentlyContinue | Select-Object -Last 20
    exit 1
}
Ok "panel listening on $base"
Start-Sleep -Seconds 1

Step 'auth'
try {
    $null = Invoke-WebRequest -Uri "$base/api/status" -UseBasicParsing -TimeoutSec 3
    No 'unauthenticated /api/status should be 401'
} catch { Ok 'unauthenticated request rejected' }

$login = Invoke-WebRequest -Uri "$base/api/login" -Method POST `
    -Body '{"password":"test1234"}' -ContentType 'application/json' `
    -SessionVariable sess -UseBasicParsing
if ($login.StatusCode -eq 200) { Ok 'login ok' } else { No 'login failed' }

$csrf = ($sess.Cookies.GetCookies($base) | Where-Object Name -eq 'wtm_csrf').Value
if ($csrf) { Ok 'csrf cookie issued' } else { No 'missing csrf cookie' }

function Get-Status { Invoke-RestMethod -Uri "$base/api/status" -WebSession $sess -TimeoutSec 5 }
function Post-Json($path, $obj) {
    Invoke-RestMethod -Uri "$base$path" -Method POST -WebSession $sess `
        -Body ($obj | ConvertTo-Json -Compress) -ContentType 'application/json' `
        -Headers @{ 'X-CSRF' = $csrf } -TimeoutSec 10
}
function Wait-State($want, $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $s = Get-Status
        if ($s.state -eq $want) { return $s }
        Start-Sleep -Milliseconds 500
    }
    return (Get-Status)
}
function Wait-Job-Done($seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $s = Get-Status
        if ($s.job -and ($s.job.phase -eq 'DONE' -or $s.job.phase -eq 'FAILED')) { return $s.job }
        Start-Sleep -Milliseconds 500
    }
    return (Get-Status).job
}

Step 'server autostart'
$s = Wait-State 'RUNNING' 90
if ($s.state -eq 'RUNNING') { Ok "server ready (uptime=$($s.uptime)s)" }
else { No "server not ready, state=$($s.state)" }

Step 'players and tps parsing'
$null = Post-Json '/api/command' @{ line = 'list' }
Start-Sleep -Milliseconds 800
$s = Get-Status
if ($s.online -eq 2) { Ok 'players parsed = 2' } else { No "players parse failed: $($s.online)" }
$null = Post-Json '/api/command' @{ line = 'tick query' }
Start-Sleep -Milliseconds 800
$s = Get-Status
if ($s.tps -gt 19 -and $s.tps -le 20) { Ok "tps parsed = $($s.tps)" } else { No "tps parse failed: $($s.tps)" }

Step 'backup'
$null = Post-Json '/api/backup' @{}
$job = Wait-Job-Done 120
if ($job -and $job.phase -eq 'DONE') { Ok "backup done ($($job.stage))" } else { No "backup failed: $($job.stage)" }

$list = Invoke-RestMethod -Uri "$base/api/backups" -WebSession $sess
if ($list.items.Count -ge 1) {
    Ok "snapshots=$($list.items.Count) newest=$($list.items[0].created) size=$($list.items[0].size)"
} else { No 'snapshot list empty' }
$snapId = $list.items[0].id

Step 'world intact after backup'
$mcaPath = Join-Path $smoke 'server\world\region\r.0.0.mca'
$bytes = [System.IO.File]::ReadAllBytes($mcaPath)
$tail = [System.Text.Encoding]::ASCII.GetString($bytes, $bytes.Length - 7, 7)
if ($bytes.Length -eq 8199 -and $tail -eq 'CHUNK-A') { Ok 'world file untouched (header + marker)' }
else { No "world file changed: len=$($bytes.Length) tail=$tail" }

Step 'restore'
$null = Post-Json '/api/restore' @{ id = $snapId }
$job = Wait-Job-Done 180
if ($job -and $job.phase -eq 'DONE') { Ok 'restore done' } else { No "restore failed: $($job.stage)" }
Start-Sleep -Seconds 2
$s = Wait-State 'RUNNING' 60
if ($s.state -eq 'RUNNING') { Ok 'server running after restore' } else { No "state after restore: $($s.state)" }

$pre = (Invoke-RestMethod -Uri "$base/api/backups" -WebSession $sess).items |
    Where-Object kind -eq 'pre_restore'
if ($pre) { Ok 'pre_restore snapshot created' } else { No 'missing pre_restore snapshot' }

Step 'console log'
$log = Join-Path $smoke 'data\server-console.log'
if (Test-Path $log) {
    $lines = Get-Content $log
    if ($lines -match 'Done \(') { Ok "console log recorded ($($lines.Count) lines)" }
    else { No 'console log missing startup marker' }
} else { No 'console log not found' }

Step 'stop'
$null = Post-Json '/api/power' @{ action = 'stop' }
$s = Wait-State 'STOPPED' 60
if ($s.state -eq 'STOPPED') { Ok 'server stopped' } else { No "stop failed: $($s.state)" }

Step 'resource usage'
$rss = (Get-Process -Id $proc.Id).WorkingSet64 / 1MB
Write-Host ("  wtm RSS = {0:N1} MB" -f $rss)
if ($rss -lt 120) { Ok 'lightweight target met (JVM included)' } else { No "RSS too high: $rss MB" }

Step 'exit'
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Write-Host ""
if ($fail -eq 0) { Write-Host 'SMOKE PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "SMOKE FAILED ($fail)" -ForegroundColor Red; exit 1 }
