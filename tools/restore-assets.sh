#!/bin/sh
# 把仓库里的 *.b64 还原成二进制文件（图标 PNG 与发布产物）。
# 仓库通过 GitHub 的内容接口提交，只能承载文本，所以二进制以 base64 文本入库。
set -e
cd "$(dirname "$0")/.."

count=0
find . -type f -name '*.b64' | while IFS= read -r src; do
  dst="${src%.b64}"
  base64 -d "$src" > "$dst"
  echo "restored: $dst"
  count=$((count + 1))
done

echo "done. 之后可运行 ./build.sh（图标）或直接取 release-assets/ 下的安装包。"
