#!/usr/bin/env bash
# 把本仓库推到 GitHub。
#
# 用法：
#   bash tools/push_github.sh <你的GitHub用户名> [仓库名]
#
# 例：
#   bash tools/push_github.sh BlestHeart binime
#
# 前置条件（二选一）：
#   A. 装了 gh CLI 且已 gh auth login  → 脚本会自己建仓库再推
#   B. 已经在网页上手工建好空仓库      → 脚本直接推
#
# 网络：本机直连 GitHub 会超时，脚本默认走 127.0.0.1:7890 代理。
#      代理端口不一样就改下面的 PROXY，或者提前 export 好 HTTPS_PROXY。

set -euo pipefail

USER_NAME="${1:-}"
REPO_NAME="${2:-binime}"
PROXY="${PROXY:-http://127.0.0.1:7890}"

if [ -z "$USER_NAME" ]; then
  echo "用法: bash tools/push_github.sh <GitHub用户名> [仓库名]"
  exit 1
fi

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

# ---------- 0. 前置检查 ----------
if [ ! -d .git ]; then
  echo "✗ 当前目录不是 git 仓库：$PROJ"
  exit 1
fi

if ! git rev-parse HEAD >/dev/null 2>&1; then
  echo "✗ 仓库还没有任何提交"
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "! 有未提交的改动："
  git status --short
  echo
  read -r -p "要先提交吗？[y/N] " ans
  if [ "$ans" = "y" ] || [ "$ans" = "Y" ]; then
    read -r -p "提交说明: " msg
    git add -A
    git commit -m "${msg:-更新}"
  else
    echo "已取消"
    exit 1
  fi
fi

# ---------- 1. 代理 ----------
export HTTPS_PROXY="$PROXY"
export HTTP_PROXY="$PROXY"
echo "→ 代理: $PROXY"

# ---------- 2. 连通性 ----------
echo "→ 测试能否连上 GitHub ..."
if ! timeout 20 git -c "http.proxy=$PROXY" ls-remote https://github.com/octocat/Hello-World.git HEAD >/dev/null 2>&1; then
  echo "✗ 连不上 GitHub。检查代理端口（当前 $PROXY），或者直接"
  echo "   export HTTPS_PROXY=http://你的代理地址:端口"
  exit 1
fi
echo "  ✓ 网络通"

REMOTE_URL="https://github.com/${USER_NAME}/${REPO_NAME}.git"

# ---------- 3. 远端仓库 ----------
if git remote get-url origin >/dev/null 2>&1; then
  echo "→ 已有 origin: $(git remote get-url origin)"
else
  # 用 gh 自动建仓库（可选）
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "→ 检测到已登录的 gh CLI，自动创建仓库 ${USER_NAME}/${REPO_NAME} ..."
    gh repo create "${USER_NAME}/${REPO_NAME}" \
      --public \
      --description "一个能输入二进制的安卓输入法（含完整拼音引擎）" \
      --source . \
      --remote origin \
      --push || true
    if git remote get-url origin >/dev/null 2>&1; then
      echo "  ✓ 已创建并推送"
      echo
      echo "完成 → https://github.com/${USER_NAME}/${REPO_NAME}"
      exit 0
    fi
    echo "  ! gh 创建失败，回退到手工模式"
  fi

  echo "→ 添加 origin: $REMOTE_URL"
  git remote add origin "$REMOTE_URL"
fi

# ---------- 4. 推送 ----------
echo "→ 推送 main 分支 ..."
echo "  （如果提示输入账号密码：密码处填 Personal Access Token，不是登录密码）"
git -c "http.proxy=$PROXY" push -u origin main

echo
echo "完成 → https://github.com/${USER_NAME}/${REPO_NAME}"
echo
echo "接下来可以做的事："
echo "  1. 打 tag 发布 APK："
echo "     git tag -a v1.2 -m 'BinIME v1.2' && git push origin v1.2"
echo "     然后在网页 Releases 里基于这个 tag 发布，上传 build/BinIME.apk"
echo "  2. 仓库描述 / 话题（topics）在网页右侧 About 里填，"
echo "     建议话题：android ime input-method pinyin binary keyboard coloros"
