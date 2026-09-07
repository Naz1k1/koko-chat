#!/usr/bin/env bash
# 使用本机真实中间件验证认证链路，测试只清理自己创建的随机账号。
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source deploy/.env
set +a
export KOKO_CHAT_AUTH_TEST=true
cd server
./mvnw -B -ntp verify "$@"
