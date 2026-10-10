#!/usr/bin/env bash
# ============================================================
# Trae 信息整合平台 - 云端服务部署脚本（Ubuntu 24.04 LTS）
# 在服务器上执行此脚本完成一键部署
# ============================================================
set -euo pipefail

# ---- 配置区（请按实际情况修改）----
# 3xui 订阅地址（已在 project_memory 中提供）
export TRAE_PROXY_SUB_URL="https://3x.955273.xyz:2096/sub/e8hac5ygz4p6rbza"
# AI Key（至少配一个；豆包国内直连推荐）
export DOUBAO_API_KEY=""           # 火山方舟 Ark API Key
# export OPENAI_API_KEY=""         # 可选：GPT（经代理）
# export GEMINI_API_KEY=""         # 可选：Gemini（经代理）
# 抓取平台密钥（可选，缺失时对应平台降级为空）
# export YOUTUBE_API_KEY=""
# export X_BEARER_TOKEN=""
# export SERPAPI_KEY=""
export COMPANION_TOKEN="trae-companion"
# 服务端口
PORT=8765

# ---- 1. 安装 JDK 17 ----
echo ">>> 安装 JDK 17..."
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-17-jdk-headless unzip curl
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
echo "export JAVA_HOME=$JAVA_HOME" | sudo tee -a /etc/profile.d/trae.sh
echo "JAVA_HOME=$JAVA_HOME"

# ---- 2. 安装 Xray-core（云端代理穿透）----
echo ">>> 安装 Xray-core..."
if ! command -v xray &>/dev/null; then
    XRAY_VER="v25.9.18"
    ARCH=$(dpkg --print-architecture)  # amd64 or arm64
    cd /tmp
    wget -q "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VER}/Xray-linux-64-${ARCH}.zip" -O xray.zip || \
    wget -q "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VER}/Xray-linux-64.zip" -O xray.zip
    sudo mkdir -p /usr/local/bin
    sudo unzip -o -q xray.zip -d /tmp/xray-extract
    sudo cp /tmp/xray-extract/xray /usr/local/bin/xray
    sudo chmod +x /usr/local/bin/xray
    rm -f xray.zip
    echo "Xray 安装完成: $(xray version 2>&1 | head -1)"
else
    echo "Xray 已安装: $(xray version 2>&1 | head -1)"
fi
export XRAY_PATH=/usr/local/bin/xray

# ---- 3. 安装 Chromium（Playwright 浏览器自动化，可选）----
echo ">>> 安装 Chromium 及 Playwright 依赖..."
sudo apt-get install -y -qq \
    libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
    libgbm1 libpango-1.0-0 libcairo2 libasound2t64
# 如果需要完整 Playwright 浏览器自动化（高风险平台登录态抓取），取消下面注释：
# npx playwright install chromium  # 需要 Node.js

# ---- 4. 创建工作目录 ----
APP_DIR=/opt/trae-info
sudo mkdir -p $APP_DIR/data
sudo chown -R $USER:$USER $APP_DIR
cd $APP_DIR

# ---- 5. 获取项目源码 ----
echo ">>> 获取项目源码..."
if [ ! -d "$APP_DIR/src" ]; then
    # 方式 A：从 Git 仓库 clone（如果有远程仓库）
    # git clone <repo-url> src

    # 方式 B：从本地上传（在本地执行 scp -r 项目源码）
    echo "请从本地用 scp 上传项目源码到此目录："
    echo "  scp -r ./server ./core ./build.gradle.kts ./settings.gradle.kts ./gradle ./gradlew $USER@<服务器IP>:$APP_DIR/"
fi

# ---- 6. 安装 Gradle 并构建 ----
echo ">>> 安装 Gradle 8.10.2..."
GRADLE_VER=8.10.2
if [ ! -d "$APP_DIR/gradle-$GRADLE_VER" ]; then
    cd /tmp
    wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"
    unzip -q "gradle-${GRADLE_VER}-bin.zip" -d $APP_DIR
    mv $APP_DIR/gradle-${GRADLE_VER} $APP_DIR/gradle-${GRADLE_VER} 2>/dev/null || true
fi
export PATH=$APP_DIR/gradle-${GRADLE_VER}/bin:$PATH
echo "Gradle: $(gradle --version 2>&1 | grep 'Gradle ' | head -1)"

# ---- 7. 构建服务端 fat-jar / 发行版 ----
echo ">>> 构建服务端发行版..."
cd $APP_DIR
# gradle :server:installDist --no-daemon -q  # 方式1：发行版目录
gradle :server:distZip --no-daemon -q          # 方式2：zip 包
SERVER_ZIP=$(ls $APP_DIR/server/build/distributions/*.zip 2>/dev/null | head -1)
if [ -n "$SERVER_ZIP" ]; then
    unzip -o -q "$SERVER_ZIP" -d $APP_DIR/
    SERVER_DIR=$(ls -d $APP_DIR/server-*/ 2>/dev/null | head -1)
    echo "服务端发行版已解压到: $SERVER_DIR"
else
    echo "!!! 构建失败，请检查编译错误"
    exit 1
fi

# ---- 8. 配置 systemd 服务（开机自启 + 崩溃重启）----
echo ">>> 配置 systemd 服务..."
SERVICE_FILE=/etc/systemd/system/trae-info.service
sudo tee $SERVICE_FILE > /dev/null <<EOF
[Unit]
Description=Trae Information Integration Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=$SERVER_DIR/bin/server
Restart=on-failure
RestartSec=10
StandardOutput=append:$APP_DIR/data/server.log
StandardError=append:$APP_DIR/data/server.log

[Install]
WantedBy=multi-user.target
EOF

# 写入环境变量文件
cat > $APP_DIR/.env <<EOF
TRAE_PROXY_SUB_URL=$TRAE_PROXY_SUB_URL
XRAY_PATH=$XRAY_PATH
DOUBAO_API_KEY=$DOUBAO_API_KEY
COMPANION_TOKEN=$COMPANION_TOKEN
EOF

sudo systemctl daemon-reload
sudo systemctl enable trae-info
sudo systemctl start trae-info
sleep 3
sudo systemctl status trae-info --no-pager -l | head -20

echo ""
echo ">>> 部署完成！"
echo "服务地址: http://$(hostname -I | awk '{print $1}'):${PORT}"
echo "日志: tail -f $APP_DIR/data/server.log"
echo "管理: sudo systemctl {start|stop|restart|status} trae-info"

# ---- 9. 防火墙放行端口 ----
echo ">>> 防火墙配置..."
sudo ufw allow ${PORT}/tcp 2>/dev/null || true
echo ">>> 请在腾讯云控制台安全组中放行 TCP ${PORT} 端口"
