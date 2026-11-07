#!/usr/bin/env bash
# ============================================================
# 下载 JVM 单测依赖到 $TOOLS/test-libs（refactor/oss 2026-11-06）。只从 Maven Central 拉，按 sha256 校验。
#   TOOLS=/opt/bixby-tools bash tools/ci/fetch_test_deps.sh
# 已存在且校验通过的文件跳过。
# ============================================================
set -euo pipefail
TOOLS="${TOOLS:-$HOME/onesay-tools}"
D="$TOOLS/test-libs"; mkdir -p "$D"
M="https://repo1.maven.org/maven2"
while read -r path sha; do
  [ -n "$path" ] || continue
  f="$D/$(basename "$path")"
  if [ -f "$f" ] && echo "$sha  $f" | sha256sum -c --status; then echo "ok   $(basename "$f")"; continue; fi
  echo "get  $(basename "$f")"
  curl -fsSL --retry 3 -o "$f.tmp" "$M/$path"
  echo "$sha  $f.tmp" | sha256sum -c --status || { echo "sha256 不符：$path" >&2; rm -f "$f.tmp"; exit 1; }
  mv "$f.tmp" "$f"
done <<'LIST'
junit/junit/4.13.2/junit-4.13.2.jar 8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3
org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar 66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9
com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79
com/ibm/icu/icu4j/72.1/icu4j-72.1.jar 3df572b240a68d13b5cd778ad2393e885d26411434cd8f098ac5987ea2e64ce3
LIST
