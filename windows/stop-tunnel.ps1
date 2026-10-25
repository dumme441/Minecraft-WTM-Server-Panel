$ErrorActionPreference = "SilentlyContinue"
Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" |
  Where-Object { $_.CommandLine -like '*wtm_panel*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }