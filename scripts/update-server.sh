#!/usr/bin/env bash
# ============================================================
# Trae 信息整合平台 - 服务端增量更新脚本
# 在服务器上执行：bash update-server.sh
# 功能：重新构建 + 配置 LAS 视觉通道 + 重启 + 测试
# ============================================================
set -euo pipefail

APP_DIR=/opt/trae-info
GRADLE_VER=8.10.2
export PATH=$APP_DIR/gradle-${GRADLE_VER}/bin:$PATH
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

cd $APP_DIR

echo ">>> [1/6] 检查源码是否已更新..."
if [ ! -d "$APP_DIR/server/src/main/kotlin/com/trae/info/server/ai/VisionModelRouter.kt" ]; then
    echo "!!! 未找到 VisionModelRouter.kt，请先将本地修改的源码同步到服务器"
    echo "    方式1（Git）：在本地 git push，服务器 git pull"
    echo "    方式2（scp）：scp -r server core 用户名@服务器IP:$APP_DIR/"
    exit 1
fi
echo "源码已就绪"

echo ">>> [2/6] 安装视频分析依赖（faster-whisper / ffmpeg）..."
sudo apt-get install -y -qq ffmpeg python3-pip >/dev/null 2>&1 || true
pip3 install --quiet faster-whisper 2>/dev/null || true

echo ">>> [3/6] 构建服务端发行版..."
cd $APP_DIR
gradle :server:distZip --no-daemon -q
SERVER_ZIP=$(ls $APP_DIR/server/build/distributions/*.zip 2>/dev/null | head -1)
SERVER_DIR=$(ls -d $APP_DIR/server-*/ 2>/dev/null | head -1)
unzip -o -q "$SERVER_ZIP" -d $APP_DIR/
SERVER_DIR=$(ls -d $APP_DIR/server-*/ 2>/dev/null | head -1)
echo "构建完成: $SERVER_DIR"

echo ">>> [4/6] 配置 LAS 视觉 API Key..."
# Key 由环境变量 LAS_API_KEY 提供，禁止硬编码（见 PROJECT_SYSTEM.md §九 铁律 7）
if [ -z "$LAS_API_KEY" ]; then
    echo "警告：未设置 LAS_API_KEY 环境变量，跳过 .env 写入" >&2
elif ! grep -q "LAS_API_KEY" $APP_DIR/.env 2>/dev/null; then
    printf 'LAS_API_KEY="%s"\n' "$LAS_API_KEY" | sudo tee -a $APP_DIR/.env >/dev/null
    echo "LAS API Key 已写入 .env"
else
    echo "LAS API Key 已存在，跳过"
fi

echo ">>> [5/6] 重启服务..."
sudo systemctl restart trae-info
sleep 5
sudo systemctl status trae-info --no-pager -l | head -15

echo ""
echo ">>> [6/6] 测试视觉模型健康状态..."
sleep 3
echo "--- 视觉模型健康状态 ---"
curl -s http://localhost:8765/api/admin/vision-models | python3 -m json.tool 2>/dev/null || curl -s http://localhost:8765/api/admin/vision-models

echo ""
echo ">>> 更新完成！"
echo "服务地址: http://$(hostname -I | awk '{print $1}'):8765"
echo "日志: tail -f $APP_DIR/data/server.log"
