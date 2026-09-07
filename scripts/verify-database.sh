#!/usr/bin/env bash
# 在临时测试库验证迁移、唯一约束和事务回滚，不修改业务库的数据。
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source deploy/.env
set +a
export KOKO_CHAT_MYSQL_TEST_URL="jdbc:mysql://${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"
export KOKO_CHAT_MYSQL_TEST_USER=root
export KOKO_CHAT_MYSQL_TEST_PASSWORD="$MYSQL_ROOT_PASSWORD"
cd server
./mvnw -B -ntp -Dtest=MySqlSchemaTest test "$@"
