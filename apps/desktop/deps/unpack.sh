#!/bin/sh
# [workflow-1120] 把 deps/java.lock 里的依赖解进 classes 目录（打进同一个 jar，运行时不用 Class-Path）。
# 去掉签名、module-info、各家的 MANIFEST；保留 META-INF/LICENSE、NOTICE、services（改名进 META-INF/onesay-third-party/<jar>/）。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${DEPS_DIR:-$HERE/../build/deps}
OUT=${1:?classes 目录}
T=$(mktemp -d)
grep -v '^#' "$HERE/java.lock" | while read -r path sum; do
  [ -z "$path" ] && continue
  j="$DIR/$(basename "$path")"
  n=$(basename "$path" .jar)
  rm -rf "$T/x" && mkdir -p "$T/x"
  (cd "$T/x" && unzip -q -o "$j")
  mkdir -p "$OUT/META-INF/onesay-third-party/$n"
  for m in "$T"/x/META-INF/LICENSE* "$T"/x/META-INF/NOTICE* "$T"/x/META-INF/*.txt; do [ -f "$m" ] && cp "$m" "$OUT/META-INF/onesay-third-party/$n/"; done
  if [ -d "$T/x/META-INF/services" ]; then mkdir -p "$OUT/META-INF/services"; for s in "$T"/x/META-INF/services/*; do cat "$s" >> "$OUT/META-INF/services/$(basename "$s")"; done; fi
  rm -rf "$T/x/META-INF" "$T/x/module-info.class"
  find "$T/x" -name module-info.class -delete
  cp -r "$T/x/." "$OUT/"
done
rm -rf "$T"
