#!/usr/bin/env bash
# 两个独立 JVM 使用随机临时数据库与专属 MQ 命名空间，不停止业务节点或中间件。
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source deploy/.env
set +a
export KOKO_CHAT_MULTI_NODE_TEST=true
export KOKO_CHAT_MYSQL_TEST_URL="jdbc:mysql://${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"
export KOKO_CHAT_MYSQL_TEST_PASSWORD="$MYSQL_ROOT_PASSWORD"
cd server
./mvnw -B -ntp -Dtest=MultiNodeRecoveryTest test "$@"
