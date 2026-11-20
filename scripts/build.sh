#!/usr/bin/env bash
# 一键构建：前端产物写入 backend/src/main/resources/static，再打包成可执行 jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  npm install
fi
npm run build

cd "$ROOT/backend"
mvn clean package -DskipTests

cp target/blog.jar "$ROOT/blog.jar"
echo "构建完成：$ROOT/blog.jar"
