#!/usr/bin/env bash
# 构建 WTM：javac 编译 + jar 打包，零外部依赖。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
CLASSES="$OUT/classes"

rm -rf "$CLASSES"
mkdir -p "$CLASSES"

find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
echo "编译 $(wc -l < "$OUT/sources.txt") 个源文件…"
javac -encoding UTF-8 --release 21 -d "$CLASSES" "@$OUT/sources.txt"

jar --create --file "$OUT/wtm.jar" --main-class wtm.Main -C "$CLASSES" .
echo "完成: $OUT/wtm.jar ($(stat -c%s "$OUT/wtm.jar") bytes)"

echo "运行自检…"
java -Dfile.encoding=UTF-8 -cp "$CLASSES" wtm.test.SelfTest
