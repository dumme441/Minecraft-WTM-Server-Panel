# Windows 静默启动器

面板只监听服务器的 `127.0.0.1`，所以本机必须先建 SSH 隧道。
传统 `.bat` 会弹一个黑框；这里用 `wscript` 跑 VBS，全程无窗口。

## 用法

1. 准备一个免密登录服务器的私钥（例如 `~\.ssh\wtm_panel`），
   并把公钥写进服务器的 `~/.ssh/authorized_keys`。
2. 复制 `launcher.vbs.example` 为 `WTM面板.vbs`，改掉三处占位：
   `sshExe`、`keyFile`、`host`。
3. 双击 `WTM面板.vbs`：后台建隧道，2.5 秒后自动打开浏览器。
4. 断开：运行 `stop-tunnel.ps1`（可再包一层 VBS 隐藏窗口）。

## 提示

- `ExitOnForwardFailure=yes` 保证端口被占用时进程立刻退出，不会静默挂两个隧道。
- 如果服务器上的 Windows 自带 OpenSSH 损坏（`ssh -V` 都报错），
  可以把 Git for Windows 的 `usr\bin\ssh.exe` 连同 `msys-*.dll` 一起打包到 `WTM\bin`，
  再把 `sshExe` 指向它。