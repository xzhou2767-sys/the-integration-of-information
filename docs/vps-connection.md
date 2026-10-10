# 腾讯云 VPS 连接信息工程文件

> 版本：1.0｜导出日期：2026-09-22｜来源：`.trae/skills/trae-server-deploy/SKILL.md`（已验证事实）
> 用途：服务器部署/运维所需连接与通道信息的单一汇总文件。
> **安全提示**：本文件含基础设施信息，请勿公开分发；凭据本身不落文档（见 §四）。

## 一、服务器基本信息

| 项 | 值 |
|---|---|
| 云厂商 | 腾讯云（CVM 控制台可管理） |
| 公网 IP | `<VPS_IP>`（脱敏占位符——公开仓库不含真实地址，本地部署请替换为实际 IP） |
| SSH 端口 | `<SSH_PORT>`（默认 22） |
| SSH 登录用户 | `<SSH_USER>`（sudo 免密 NOPASSWD） |
| 认证方式 | **密码认证**（经 SSH_ASKPASS 非交互实现；`PubkeyAuthentication=no`）——本项目未使用私钥 |
| 操作系统 | Linux（Ubuntu 系） |
| 硬件约束 | 3.7G 内存（同一时间只允许一个 Gradle 构建，并发构建会耗尽内存导致整机假死） |
| 安全组放行 | `<SSH_PORT>`（SSH）、`<UPLOAD_PORT>`（临时文件上传通道）、`<API_PORT>`（应用 API，本地默认 8765） |

## 二、SSH 连接方法（通道一，首选——已验证最可靠）

OrcaTerm 网页终端在高负载时会断连且 canvas 无法读输出；直连 SSH 稳定可靠。

### 1. askpass 凭据文件（工作区根目录，已存在）

- 文件：`.tmp-askpass.cmd`（工作区根目录 `c:\Users\ASUS\Documents\trae_projects\integration of information project\`），内容仅一行 `@echo <服务器密码>`。
- 密码本身**不写入任何文档**；文件丢失时需向服务器管理员重新获取。

### 2. 连接命令模板（PowerShell）

```powershell
$env:SSH_ASKPASS = "<工作区绝对路径>\.tmp-askpass.cmd"
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "x"
# 首次连接加 -o StrictHostKeyChecking=accept-new
& ssh.exe -o ConnectTimeout=20 -o PreferredAuthentications=password -o PubkeyAuthentication=no <SSH_USER>@<VPS_IP> '<远程命令>'
```

SCP 上传同理：

```powershell
& scp.exe -o PreferredAuthentications=password -o PubkeyAuthentication=no <本地文件> <SSH_USER>@<VPS_IP>:<远程路径>
```

### 3. 引号与脚本规则（血泪教训）

- 远程命令用单引号包裹；内部双引号用 `\"` 转义；**避免**在单引号命令里塞 `python3 -c "..."`（引号互相吞掉）。
- 复杂逻辑：本地 Write → scp 上传 → 执行 → 删除；或 heredoc（`<< 'EOF'` 防本地展开，heredoc 内不出现未配对双引号）。
- `pkill -f gradle` 在 SSH 单引号命令里会匹配命令行自身导致自杀断连（exit -1）——分开执行或 `pgrep -fl` 先确认。
- scp 单文件到 `/tmp/` 再 `cp` 到目标路径，保留时间戳。

### 4. 环境注意

- PowerShell 用 `;` 不用 `&&`；curl 用 `curl.exe`；profile 的 PSSecurityException 报错是噪音，忽略。
- 本地打包用 Windows 自带 `tar.exe -czf`（正斜杠路径）。

## 三、服务器与应用布局

| 项 | 值 |
|---|---|
| 应用目录 | `/opt/trae-info/` |
| systemd 单元 | `trae-info`（重启：`sudo systemctl restart trae-info`） |
| 应用端口 | `8765`（`/api/feed`、`/api/debug/metrics` 等） |
| 服务器源码范围 | 仅 `core/src`、`server/src`、根 `build.gradle.kts`、`settings.gradle.kts`（无 `:client-app`） |
| JDK17 | 服务器 `/usr/lib/jvm/java-17-openjdk-amd64` |
| Gradle | 服务器 `/opt/trae-info/gradle-8.10.2/bin/gradle` |
| 数据/配置 | `/opt/trae-info/data/`（`config.json`、`server.log`、`translations.json` 等）、`.env` |
| 本地工具链 | JDK `C:\toolchain\trae-tc\jdk-17.0.2`；Gradle `C:\toolchain\trae-tc\gradle-8.10.2\bin\gradle.bat` |

发布标准流程（构建→部署→验证）全文见 `.trae/skills/trae-server-deploy/SKILL.md`。

## 四、凭据安全约定

- 服务器密码只存在于工作区 `.tmp-askpass.cmd`；**禁止**写入文档、日志、git 或回传到对话。
- `data/config.json` 内的令牌/Key **禁止读取到本地回传**；凭据读取一律需用户显式批准。
- SSH/凭据使用属安全敏感操作：每次会话需用户显式批准（批准范围以当次任务为准）。

## 五、备用通道与应急

| 通道 | 用途 | 要点 |
|---|---|---|
| OrcaTerm 网页终端（https://orcaterm.com/terminal，会话 `trae-server`） | 仅当 SSH 22 不通时 | Monaco 编辑器注入命令（`window.monaco.editor.getEditors()[0].setValue(...)` + `getSupportedActions().find(a=>a.id.includes('send-command')).run()`）；终端为 canvas 渲染，输出只能截图读图；有独立登录态 |
| 80 端口临时 Python 服务 | OrcaTerm 文件上传备用 | POST 写 `/tmp/trae-source.zip` |
| 腾讯云 CVM 控制台 | 假死应急重启 | ping 通但 SSH/HTTP 全超时（banner exchange 超时）时：停止高频探测（间隔≥30s 防 CC 防护），请用户在控制台对实例 `<VPS_IP>` 执行重启（必要时强制重启）；恢复后先 `pkill -f gradle`、`uptime; free -m` 确认负载 |
