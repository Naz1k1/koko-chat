package dev.koko.chat.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显式启用的 MySQL 8.4 迁移验证：只创建、使用和清理本次生成的独立测试库。
 * 需要建库权限；不对应用库执行 clean 或 DROP，默认构建在未配置连接时跳过。
 */
@EnabledIfEnvironmentVariable(named = "KOKO_CHAT_MYSQL_TEST_URL", matches = ".+")
class MySqlSchemaTest {
    @Test
    void migratesOnceAndEnforcesMessageMembershipAndOutboxConstraints() throws Exception {
        String url = System.getenv("KOKO_CHAT_MYSQL_TEST_URL");
        String user = System.getenv().getOrDefault("KOKO_CHAT_MYSQL_TEST_USER", "root");
        String password = System.getenv().getOrDefault("KOKO_CHAT_MYSQL_TEST_PASSWORD", "");
        String schema = "koko_chat_test_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("8.4.");
            execute(connection, "CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway flyway = Flyway.configure().dataSource(url, user, password)
                        .schemas(schema).defaultSchema(schema).createSchemas(false)
                        .initSql("SET time_zone = '+00:00'").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
                flyway.validate();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                connection.setCatalog(schema);
                execute(connection, "SET time_zone = '+00:00'");
                assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'"))
                        .isEqualTo(11);

                // 测试账号只存在于临时库，哈希字段使用不可登录的占位数据。
                execute(connection, "INSERT INTO app_user(id, account, password_hash, nickname) VALUES (1, 'alice', 'test-only-hash', '小甲'), (2, 'bob', 'test-only-hash', '小乙')");
                execute(connection, "INSERT INTO conversation(id, type, direct_key, created_by) VALUES (10, 'DIRECT', '1:2', 1)");
                execute(connection, "INSERT INTO conversation_member(conversation_id, user_id, membership_epoch, join_seq, last_read_seq) VALUES (10, 1, '00000000-0000-0000-0000-000000000001', 1, 0)");

                connection.setAutoCommit(false);
                execute(connection, "UPDATE conversation SET latest_seq = 1 WHERE id = 10");
                execute(connection, message(100, 1, "client-1"));
                execute(connection, outbox("00000000-0000-0000-0000-000000000100", 100));
                connection.commit();
                connection.setAutoCommit(true);

                // 两组唯一约束分别保护客户端重试幂等和会话内顺序。
                expectSqlError(connection, message(101, 2, "client-1"), 1062);
                expectSqlError(connection, message(102, 1, "client-2"), 1062);
                expectSqlError(connection, message(103, 0, "client-3"), 3819);
                expectSqlError(connection, outbox("00000000-0000-0000-0000-000000000999", 999), 1452);

                // 当前成员周期可以更新，旧消息保留原周期，不被成员外键级联修改或删除。
                execute(connection, "UPDATE conversation_member SET membership_epoch = '00000000-0000-0000-0000-000000000002', join_seq = 2, last_read_seq = 1 WHERE conversation_id = 10 AND user_id = 1");
                assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE sender_membership_epoch = '00000000-0000-0000-0000-000000000001'"))
                        .isEqualTo(1);

                // 待处理申请按无序用户对去重；拒绝后可以重新申请，历史记录不占唯一键。
                execute(connection, "INSERT INTO friend_request(id, sender_id, receiver_id) VALUES (1, 1, 2)");
                expectSqlError(connection, "INSERT INTO friend_request(id, sender_id, receiver_id) VALUES (2, 2, 1)", 1062);
                execute(connection, "UPDATE friend_request SET status = 'REJECTED', handled_at = CURRENT_TIMESTAMP(3) WHERE id = 1");
                execute(connection, "INSERT INTO friend_request(id, sender_id, receiver_id) VALUES (2, 2, 1)");

                // 确认发布时必须同时清空租约，避免持有陈旧认领状态。
                execute(connection, "UPDATE message_outbox SET status = 'PUBLISHING', lease_token = '00000000-0000-0000-0000-000000000200', lease_until = CURRENT_TIMESTAMP(3) + INTERVAL 30 SECOND WHERE message_id = 100");
                expectSqlError(connection, "UPDATE message_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3) WHERE message_id = 100", 3819);
                execute(connection, "UPDATE message_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3), lease_token = NULL, lease_until = NULL WHERE message_id = 100");

                // 事务回滚时，序号、消息及 Outbox 一同撤销，不能留下孤立通知。
                connection.setAutoCommit(false);
                execute(connection, "UPDATE conversation SET latest_seq = 2 WHERE id = 10");
                execute(connection, message(200, 2, "client-rollback"));
                execute(connection, outbox("00000000-0000-0000-0000-000000000201", 200));
                connection.rollback();
                connection.setAutoCommit(true);
                assertThat(count(connection, "SELECT latest_seq FROM conversation WHERE id = 10")).isEqualTo(1);
                assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE id = 200")).isZero();
                assertThat(count(connection, "SELECT COUNT(*) FROM message_outbox WHERE message_id = 200")).isZero();
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                // schema 只由固定前缀和本次 UUID 组成，不接受外部传入的删除目标。
                execute(connection, "DROP DATABASE `" + schema + "`");
            }
        }
    }

    private static String message(long id, long seq, String clientId) {
        return "INSERT INTO message(id, conversation_id, seq, sender_id, sender_membership_epoch, client_msg_id, type, body, body_hash) VALUES ("
                + id + ", 10, " + seq + ", 1, '00000000-0000-0000-0000-000000000001', '" + clientId
                + "', 'TEXT', JSON_OBJECT('text', '中文消息'), UNHEX(SHA2('中文消息', 256)))";
    }

    private static String outbox(String eventId, long messageId) {
        return "INSERT INTO message_outbox(event_id, message_id, payload) VALUES ('" + eventId + "', " + messageId
                + ", JSON_OBJECT('messageId', '" + messageId + "'))";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void expectSqlError(Connection connection, String sql, int code) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
