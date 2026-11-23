#!/bin/sh
# [workflow-1120] 按 deps/java.lock 下载 Java 依赖到 $DEPS_DIR（默认 build/deps），逐个核对 SHA-256；已存在且校验通过的不重下。
# 镜像：MAVEN_REPO=https://repo1.maven.org/maven2（默认）
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${DEPS_DIR:-$HERE/../build/deps}
REPO=${MAVEN_REPO:-https://repo1.maven.org/maven2}
mkdir -p "$DIR"
grep -v '^#' "$HERE/java.lock" | while read -r path sum; do
  [ -z "$path" ] && continue
  f="$DIR/$(basename "$path")"
  if [ -f "$f" ] && [ "$(sha256sum "$f" | cut -c1-64)" = "$sum" ]; then continue; fi
  curl -fsSL -o "$f.part" "$REPO/$path"
  got=$(sha256sum "$f.part" | cut -c1-64)
  if [ "$got" != "$sum" ]; then echo "校验失败：$path（期望 $sum，实际 $got）" >&2; rm -f "$f.part"; exit 1; fi
  mv "$f.part" "$f"
done
echo "$DIR"
