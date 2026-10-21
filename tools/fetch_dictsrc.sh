#!/usr/bin/env bash
# 下载 Rime 拼音词典源文件到 build/dictsrc/，供 tools/build_dict.py 编译。
#
# build/dictsrc/ 不进版本库（约 45 MB，且随时可从上游重新拉），
# 但 app/assets/pinyin.dict.gz 是编译好的成品，已经提交了，
# 所以只有想改词库/换词源时才需要跑这个脚本。
#
# 用法：
#   bash tools/fetch_dictsrc.sh
#   python tools/build_dict.py          # 编译出 app/assets/pinyin.dict.gz

set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$PROJ/build/dictsrc"
mkdir -p "$DEST"

# GitHub raw 在国内经常连不上（实测直接访问 000 超时），
# 走 gh-proxy.com 代理。如果哪天代理挂了，把 BASE 换成别的镜像，
# 或者自己挂梯子改成 https://raw.githubusercontent.com 直连。
MIRROR="${DICT_MIRROR:-https://gh-proxy.com/}"
BASE="${MIRROR}https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts"

FILES=(8105 base ext others)

for name in "${FILES[@]}"; do
  out="$DEST/$name.dict.yaml"
  if [ -f "$out" ]; then
    echo "跳过 $name.dict.yaml（已存在，$(stat -c%s "$out" 2>/dev/null || echo '?') 字节）"
    continue
  fi
  echo "下载 $name.dict.yaml ..."
  # -f 让 HTTP 4xx/5xx 变成非零退出，否则会把错误页当词典存下来
  if ! curl -fL --retry 3 --retry-delay 2 -m 120 -o "$out.part" "$BASE/$name.dict.yaml"; then
    rm -f "$out.part"
    echo "失败：$name.dict.yaml" >&2
    echo "  试过的地址：$BASE/$name.dict.yaml" >&2
    echo "  可以换镜像：DICT_MIRROR=https://mirror.example/ bash tools/fetch_dictsrc.sh" >&2
    exit 1
  fi
  mv "$out.part" "$out"
  echo "  -> $(stat -c%s "$out") 字节"
done

echo
echo "词典源就绪：$DEST"
echo "下一步：python tools/build_dict.py"
