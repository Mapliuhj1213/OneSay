#!/usr/bin/env bash
# 下载 JVM 单测依赖到 ${1:-out-test-libs}（Maven Central），并校验 SHA-256。用法：
#   bash tools/fetch_test_libs.sh /tmp/test-libs && TEST_LIBS=/tmp/test-libs bash test/run_linux.sh
set -euo pipefail
DIR="${1:-out-test-libs}"; mkdir -p "$DIR"
M=https://repo1.maven.org/maven2
while read -r sha path; do
  f="$DIR/$(basename "$path")"
  [ -s "$f" ] || curl -fsSL --retry 3 -o "$f" "$M/$path"
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "校验失败: $f" >&2; rm -f "$f"; exit 1; }
done <<'LIST'
8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3 junit/junit/4.13.2/junit-4.13.2.jar
66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79 com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar
3df572b240a68d13b5cd778ad2393e885d26411434cd8f098ac5987ea2e64ce3 com/ibm/icu/icu4j/72.1/icu4j-72.1.jar
LIST
echo "test libs OK: $DIR"
