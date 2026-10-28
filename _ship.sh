#!/usr/bin/env bash
set -euo pipefail
cd "G:/DeepSeek Harness/binfloater-android"
export GIT_TERMINAL_PROMPT=0
PROXY="-c http.proxy=http://127.0.0.1:7890"

echo "== 1. commit =="
git add -A
git -c user.name="BlestHeart" -c user.email="BlestHeart@users.noreply.github.com" commit -q -m "改名「编码悬浮窗」：名字和简介跟上实际能力

早就不止二进制了——Base64 双向、HEX 输出、方向自动识别、五种显示格式。
名字还停在 v2.0 的「二进制悬浮窗」。

- strings.xml: app_name 编码悬浮窗
- MainActivity: 标题 / 副标题 / 怎么用 / 开关描述
- FloatingService: 面板标题、通知标题
- README: 重写头部、它解决什么问题、界面表、功能列表
- CHANGELOG: 2.5 段补改名说明与 257 项断言
- push_github.sh: 仓库 description
- DEVELOPMENT.md: 标题栏示意图

顺带修准一处措辞：HEX 只有输出没有输入（BinCore 没有 hexToText，
autoDetect 只认 0/1 串和 Base64），不再声称「HEX 也能解回文字」。"
git log --oneline -1

echo "== 2. move tag v2.5 =="
git tag -d v2.5
git -c user.name="BlestHeart" -c user.email="BlestHeart@users.noreply.github.com" tag -a v2.5 -m "v2.5：检查更新 + 改名编码悬浮窗"
git rev-parse v2.5^{commit}

echo "== 3. push =="
git $PROXY push origin main
git $PROXY push -f origin refs/tags/v2.5
echo "PUSH OK"
