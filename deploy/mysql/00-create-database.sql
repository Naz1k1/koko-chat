-- 仅用于已有 MySQL 8.4 实例的建库步骤，不创建账号、不修改已有数据。
-- Docker Compose 已通过 MYSQL_DATABASE 自动建库时，无需重复执行本文件。
-- 使用自定义库名时，须同步调整此处库名及服务端 MYSQL_DATABASE。
CREATE DATABASE IF NOT EXISTS koko_chat
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
