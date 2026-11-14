#!/usr/bin/env bash
# 可选：下载官方 sherpa-onnx 唤醒词模型并打包成 app/assets/kws_model.zip（本地自用构建）。
# 模型权重的许可在上游尚未明确（见根目录 THIRD_PARTY_NOTICES / README），所以 OneSay 仓库与发布的 APK 都不带它。
# 校验：用同一 release 的 checksum.txt 核对 sha256，不一致就失败退出。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME=sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/$NAME.tar.bz2" "$BASE/$NAME.tar.bz2"
curl -fsSL -o "$TMP/checksum.txt" "$BASE/checksum.txt"
# checksum.txt 两种排版都可能（"hash  name" 或 "name hash"）：取该行里 64 位十六进制那一段
want="$(grep "$NAME.tar.bz2" "$TMP/checksum.txt" | tr -s ' \t' '\n' | grep -E '^[0-9a-f]{64}$' | head -1)"
got="$(sha256sum "$TMP/$NAME.tar.bz2" | awk '{print $1}')"
[ -n "$want" ] && [ "$want" = "$got" ] || { echo "sha256 不一致（want=${want:-无} got=$got）" >&2; exit 1; }
tar -xjf "$TMP/$NAME.tar.bz2" -C "$TMP"
D="$TMP/$NAME"; mkdir -p "$TMP/z"
cp "$(ls "$D"/encoder-*chunk-16-left-64.onnx | grep -v int8 | head -1)" "$TMP/z/encoder.onnx"
cp "$(ls "$D"/decoder-*chunk-16-left-64.onnx | grep -v int8 | head -1)" "$TMP/z/decoder.onnx"
cp "$(ls "$D"/joiner-*chunk-16-left-64.onnx | grep -v int8 | head -1)" "$TMP/z/joiner.onnx"
cp "$D/tokens.txt" "$TMP/z/tokens.txt"
: > "$TMP/z/keywords.txt"
( cd "$TMP/z" && zip -q -X "$ROOT/app/assets/kws_model.zip" encoder.onnx decoder.onnx joiner.onnx tokens.txt keywords.txt )
echo "OK: app/assets/kws_model.zip（sha256 $got 的官方包；不要提交到仓库）"
