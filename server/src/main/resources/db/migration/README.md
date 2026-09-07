# Flyway 迁移

`V1__create_chat_schema.sql` 创建首期 9 张业务表，由 `local` 配置在应用启动时执行；默认 `skeleton` 不连接数据库或执行迁移。

数据库本身由 Compose 或 `deploy/mysql/00-create-database.sql` 预先创建。不要手工导入 V1 后再让 Flyway 重复执行，也不要把 V1 复制成另一份独立维护的建表文件。

已执行的迁移保持不变，后续结构调整创建 V2、V3 等新文件。具体字段、事务边界与测试方法见 [数据库说明](../../../../../../docs/database.md)。
