#!/usr/bin/env bash
# 用临时后端进程联调多个 Kotlin 客户端；结束时停止进程并定向清理本次测试账号。
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source deploy/.env
set +a
export KOKO_CHAT_TEST_ACCOUNT_PREFIX="dsk_$(python3 -c 'import uuid; print(uuid.uuid4().hex[:20])')"
export HTTP_PORT="${KOKO_CHAT_VERIFY_HTTP_PORT:-18080}"
export IM_PORT="${KOKO_CHAT_VERIFY_IM_PORT:-18081}"
export KOKO_CHAT_TEST_API_BASE="http://127.0.0.1:$HTTP_PORT"
export KOKO_CHAT_TEST_IM_URL="ws://127.0.0.1:$IM_PORT/im"
export KOKO_CHAT_TEST_STAGE=read-receipts
# 只使用空闲端口，防止把另一个服务的健康响应误认为本次启动成功。
python3 - <<'PORTS'
import os, socket
for key in ('HTTP_PORT', 'IM_PORT'):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', int(os.environ[key])))
PORTS
log_dir=$(mktemp -d "${TMPDIR:-/tmp}/koko-chat-auth.XXXXXX")
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  # 前缀由脚本生成且只有十六进制字符，不接受外部账号或任意 SQL 输入。
  docker compose --env-file deploy/.env -f deploy/compose.yaml exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE"' <<SQL
DELETE FROM device_cursor WHERE conversation_id IN (SELECT id FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')));
DELETE FROM message_outbox WHERE message_id IN (SELECT id FROM message WHERE conversation_id IN (SELECT id FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i'))));
DELETE FROM message WHERE conversation_id IN (SELECT id FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')));
DELETE FROM conversation_member WHERE conversation_id IN (SELECT id FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')));
DELETE FROM group_command WHERE conversation_id IN (SELECT id FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')));
DELETE FROM conversation WHERE created_by IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i'));
DELETE FROM friendship WHERE user_id IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')) OR friend_id IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i'));
DELETE FROM friend_request WHERE sender_id IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i')) OR receiver_id IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i'));
DELETE FROM auth_session WHERE user_id IN (SELECT id FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i'));
DELETE FROM app_user WHERE account IN ('${KOKO_CHAT_TEST_ACCOUNT_PREFIX}a','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}b','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}c','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}d','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}e','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}f','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}g','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}h','${KOKO_CHAT_TEST_ACCOUNT_PREFIX}i');
SQL
}
trap cleanup EXIT
"${JAVA_HOME:?请设置 JDK 21 的 JAVA_HOME}/bin/java" -jar server/target/koko-chat-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=local > "$log_dir/server.log" 2>&1 &
server_pid=$!
ready=false
for attempt in {1..60}; do
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  if curl --fail --silent --max-time 1 "$KOKO_CHAT_TEST_API_BASE/actuator/health" >/dev/null; then ready=true; break; fi
  sleep 0.5
done
if [[ "$ready" != true ]]; then printf '后端未就绪，查看 %s/server.log\n' "$log_dir"; exit 1; fi
printf '后端已就绪，日志：%s/server.log\n' "$log_dir"
# 子 shell 返回后仍在仓库根目录执行清理；可附加本机 Gradle 工具链参数。
(cd apps/desktop && ./gradlew test --rerun-tasks "$@")
