# 常驻监控验收记录

验收环境：2026-09-08，macOS Apple Silicon、Docker Desktop，固定 Prometheus 3.14.0 与 Alertmanager 0.34.0。运行方法见 [常驻监控手册](monitoring.md)。

## 常驻与真实业务抓取

独立 `koko-chat-monitoring` 项目的两个容器均启动并通过健康检查，绑定 127.0.0.1:9090 / 9093，启用 `unless-stopped`，使用独立命名数据卷与限额日志。

临时启动既有 local 后端，常驻 Prometheus 通过 `host.docker.internal:8080` 和文件中的 Bearer 凭据成功抓取真实指标，`up` 对 koko-chat / prometheus / alertmanager 均为 1，活动规则告警为 0。结束临时后端后保留监控运行；后端停机期间出现不可达告警属于预期。

## 自动化验收

- Python 配置测试 5 项：邮件参数缺失失败、仅监控模式显式关闭邮件、特殊字符密码不执行/不进入配置日志、受限目录与凭据旋转、无效运维 token / 目标 / 模板收件人拒绝。
- 官方 promtool 校验 10 条规则和 Prometheus 配置；规则用例覆盖积压、后端不可达、Alertmanager 不可达、邮件失败及对应恢复。
- 隔离集成通过真实 Prometheus → Alertmanager → 本机 SMTP，复用正式告警规则及中文模板。SMTP 使用一次性证书并验证 STARTTLS 和 PLAIN 认证，首次 DATA 返回 451，验证自动重试；随后恢复指标，接收恢复邮件。
- 集成重启两个监控容器，重新读取 Docker 动态端口，确认先前值为 70 的指标历史和已创建的静默仍在。测试容器、网络、卷、证书均在退出时清理。

测试 SMTP 只在本机接收 example.invalid 地址，没有向外部邮箱投递。测试中缩短 Alertmanager 聚合等待，但业务规则阈值和持续时间保持正式配置。

## 实际邮件待完成部分

本次结束时，用户尚未填写 `deploy/monitoring/.env` 的五个 SMTP 参数，常驻实例运行在明确的仅监控模式。邮件实现与本机端到端验收已交付，真实邮箱的认证、服务商接收及收件箱到达尚未验证。

填写后执行 `python3 scripts/monitoring.py up` 和 `python3 scripts/monitoring.py test-email`，核对两封演练邮件。没有 SMTP 配置或实际投递结果时，不将通知标记为已开通。
