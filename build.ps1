param(
    [string]$Jdk = '',
    [string]$Out = 'build'
)

# Build WTM: javac + jar, zero external dependencies.
# Usage: .\build.ps1   (uses -Jdk, PATH, or JAVA_HOME)
# Note: this script is intentionally ASCII-only so Windows PowerShell 5.1
# can parse it without a BOM.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $root $Out
$classes = Join-Path $outDir 'classes'

function Find-Tool([string]$name) {
    if ($Jdk) {
        $candidate = Join-Path $Jdk "$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw "Cannot find $name. Install JDK 21 or pass -Jdk <jdk-bin-dir>."
}

$javac = Find-Tool 'javac'
$jar = Find-Tool 'jar'
$java = Find-Tool 'java'

Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$files = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
Write-Host "Compiling $($files.Count) source files..."
& $javac -encoding UTF-8 --release 21 -d $classes $files
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$jarPath = Join-Path $outDir 'wtm.jar'
& $jar --create --file $jarPath --main-class wtm.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

$size = (Get-Item -LiteralPath $jarPath).Length
Write-Host ("Built: {0} ({1:N0} bytes)" -f $jarPath, $size) -ForegroundColor Green

Write-Host 'Running self test...'
# 让 PowerShell 以 UTF-8 解码子进程输出，避免中文乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classes wtm.test.SelfTest
if ($LASTEXITCODE -ne 0) { throw 'self test failed' }
Write-Host 'OK' -ForegroundColor Green
