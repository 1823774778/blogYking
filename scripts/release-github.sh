#!/usr/bin/env bash
# 发布 GitHub Release：打 tag（由 gh 创建）、上传 jar、SHA-256 与更新说明
set -euo pipefail

TAG="${1:-}"
JAR="${2:-}"
NOTES="${3:-CHANGELOG.md}"
REPO="${GH_REPO:-1823774778/blogYking}"
TITLE="${RELEASE_TITLE:-$TAG}"
TARGET="${RELEASE_TARGET:-main}"

if [ -z "$TAG" ] || [ -z "$JAR" ]; then
  echo "用法: GH_REPO=1823774778/blogYking $0 v1.2.0 ./blog.jar ./release-notes/1.2.0.md"
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "jar 不存在：$JAR"
  exit 1
fi
if [ ! -f "$NOTES" ]; then
  echo "更新说明不存在：$NOTES"
  exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "需要 GitHub CLI：https://cli.github.com/"
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub CLI 未登录，请先执行：gh auth login"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${TAG#v}"
JAR_LIST="$(unzip -l "$JAR" 2>/dev/null || true)"
if ! grep -q 'BOOT-INF/classes/application.yml' <<<"$JAR_LIST"; then
  echo "不是本项目的 Spring Boot 可执行 jar：$JAR"
  exit 1
fi

MANIFEST="$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null || true)"
if ! grep -q "Implementation-Version: ${VERSION}" <<<"$MANIFEST"; then
  echo "jar 版本与 tag 不一致：tag=$VERSION"
  grep 'Implementation-Version' <<<"$MANIFEST" || true
  exit 1
fi

SHA_FILE="${JAR}.sha256"
SHA256="$(sha256sum "$JAR" | awk '{print $1}')"
printf '%s  %s\n' "$SHA256" "$(basename "$JAR")" > "$SHA_FILE"
echo "版本：$VERSION"
echo "SHA-256：$SHA256"

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG 已存在，覆盖资产与说明"
  gh release upload "$TAG" "$JAR" "$SHA_FILE" --repo "$REPO" --clobber
  gh release edit "$TAG" --repo "$REPO" --title "$TITLE" --notes-file "$NOTES"
else
  echo "创建 Release $TAG"
  gh release create "$TAG" "$JAR" "$SHA_FILE" \
    --repo "$REPO" \
    --title "$TITLE" \
    --notes-file "$NOTES" \
    --target "$TARGET"
fi

echo "完成：https://github.com/$REPO/releases/tag/$TAG"
