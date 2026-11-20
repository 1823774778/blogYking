#!/usr/bin/env bash
# OTA 更新：备份旧包 -> 替换 jar -> 重启 systemd 服务 -> 打印版本探针
set -euo pipefail

NEW_JAR="${1:-}"
if [ -z "$NEW_JAR" ] || [ ! -f "$NEW_JAR" ]; then
  echo "用法: APP_DIR=/opt/deek-blog SERVICE_NAME=deek-blog $0 /path/to/new-blog.jar"
  exit 1
fi

APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
SERVICE_NAME="${SERVICE_NAME:-deek-blog}"
PORT="${PORT:-8080}"
BACKUP_DIR="$APP_DIR/backups"
STAMP="$(date +%Y%m%d%H%M%S)"

mkdir -p "$BACKUP_DIR"
if [ -f "$APP_DIR/blog.jar" ]; then
  cp "$APP_DIR/blog.jar" "$BACKUP_DIR/blog-$STAMP.jar"
  echo "已备份旧包：$BACKUP_DIR/blog-$STAMP.jar"
fi

cp "$NEW_JAR" "$APP_DIR/blog.jar"
echo "已替换：$APP_DIR/blog.jar"

if systemctl list-unit-files 2>/dev/null | grep -q "^${SERVICE_NAME}.service"; then
  systemctl restart "$SERVICE_NAME"
  echo "已重启服务：$SERVICE_NAME"
else
  echo "未找到 systemd 服务 $SERVICE_NAME，请手动重启进程。"
fi

sleep 3
echo "版本探针："
curl -s "http://127.0.0.1:${PORT}/api/system/version" || echo "探针请求失败，请检查服务是否已启动"
echo
