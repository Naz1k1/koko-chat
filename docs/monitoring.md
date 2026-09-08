# 常驻监控与邮件通知

本机使用独立 Docker Compose 项目 `koko-chat-monitoring` 运行 Prometheus 3.14.0 与 Alertmanager 0.34.0。现有 Java / Netty 后端和数据库无需修改；本轮只补常驻监控和邮件通知。

## 配置与启动

1. 保持 Docker Desktop 运行。后端按 [运维手册](operations.md) 加载 `deploy/.env` 启动，其中 `KOKO_OPS_TOKEN` 至少 32 字符。
2. 将 [邮件配置模板](../deploy/monitoring/.env.example) 复制为 `deploy/monitoring/.env`，权限设为 600。填写 SMTP_SMARTHOST、SMTP_FROM、SMTP_TO、SMTP_USERNAME 和 SMTP_PASSWORD；密码通常是邮箱授权码。直接填写原始值，不添加 shell 引号，也不要执行 `source`。多个收件人用逗号分隔。
3. 在仓库根目录执行：

```bash
python3 scripts/monitoring.py up
python3 scripts/monitoring.py status
python3 scripts/monitoring.py test-email
```

`up` 校验配置并常驻启动两个容器。邮箱参数缺失会失败，不假装启用通知。若暂时只需采集，可明确使用 `up --monitor-only`，此模式关闭邮件接收器；填好邮箱后再次执行普通 `up` 才启用邮件。

`test-email` 向 Alertmanager 注入带有 `KokoEmailSmokeTest` 标记的合成告警，随后显式结束并发送恢复通知，不停止业务依赖。它检查邮件发送/失败计数；同时有其他告警时计数可能混合，最终请核对收件箱中的两封演练邮件。SMTP 接受也不保证未进入垃圾邮件箱。

SMTP 默认要求 TLS，验证服务器证书；通常使用 587 的 STARTTLS，465 自动使用隐式 TLS。发送者需符合邮箱服务商要求。运维 token 和 SMTP 密码分别通过只读文件挂载，不作为命令行参数、不写入受版本控制的配置。生成目录 `deploy/monitoring/runtime` 权限为 700；其中只读文件需供容器用户读取。`.env` 与 runtime 都被 Git 忽略。

修改 SMTP 参数、目标地址或轮换任一密钥后，再执行 `up`。脚本原子替换文件并重建监控容器，避免旧的文件挂载继续使用旧密钥；已有数据卷保留。后台运维 token 改动还需重启后端加载新环境。

## 查看与启停

- [Prometheus 指标与规则](http://127.0.0.1:9090)：Targets 查看抓取状态，Alerts 查看触发情况。
- [Alertmanager 告警与静默](http://127.0.0.1:9093)：查看活动告警、设置维护期静默。

```bash
python3 scripts/monitoring.py status
python3 scripts/monitoring.py stop
# 停止后需要显式再次 up，不会自动恢复被人工停止的容器。
python3 scripts/monitoring.py up
```

两个管理入口仅绑定宿主机回环地址，没有公开到局域网；不要直接将无认证的管理 API 暴露到公网。日志通过 `docker compose -f deploy/monitoring/compose.yaml logs --tail=100` 查看；排错时不要公开包含地址或认证细节的原始日志。

`restart: unless-stopped` 在进程异常退出后重启，Docker 引擎重新启动后恢复未被人工停止的容器。Docker Desktop 本身仍需运行，电脑关机或休眠期间不能采集或通知；如需全天监控，应将同一套服务部署到持续在线的机器。本轮未修改系统登录项，也没有新增后台聊天服务。

## 抓取、存储与告警策略

每 15 秒抓取并评估规则。默认目标 `host.docker.internal:8080` 适用于本机 Docker Desktop，已对回环监听的真实后端验证。可在监控 `.env` 设置 `KOKO_MONITOR_TARGETS=host-a:8080,host-b:8080`；各节点需使用这套运维凭据。Linux host-gateway 并不保证可访问仅监听 127.0.0.1 的宿主服务，需按实际网络绑定或部署网络调整目标。

Prometheus 使用独立数据卷，保留最近 15 天且限制历史块大小为 2 GB，以先达到的限制为准；WAL、活动块和临时文件仍会占用额外空间。Alertmanager 通知日志和静默也落独立数据卷，保留参数为 120 小时。停止和重建容器保留数据；不要用 `down -v` 删除数据卷。

告警覆盖后端不可达/认证失败、快照停更、MySQL/Redis/RabbitMQ/RustFS 不可用、Outbox 积压、死信、重放积压、业务处理压力。另监控 Alertmanager 不可达、邮件发送失败和 Prometheus 告警投递失败。

首次触发按原规则持续 30 秒或 1 分钟，再聚合等待 30 秒；同一告警、环境和实例合并，变化最短每 1 分钟通知一次，持续未恢复每 4 小时提醒，恢复发送邮件。节点整体不可达时抑制该节点的其他业务告警，减少重复提示。邮件含告警名、状态、级别、实例、环境、时间与中文说明，不含聊天内容或用户标识。

共享 Outbox/死信指标会在多个节点重复采集，不能直接求和。Prometheus/Alertmanager 自监控只能在剩余链路可用时告警：SMTP 故障不能靠同一个 SMTP 保证送达，整台机器宕机也无法自发邮件。相关错误可在 Prometheus 页面和日志查看；外部独立探活属于后续独立部署范围。

## 验证

本次执行结果及实际邮箱开通状态见 [验收记录](monitoring-verification.md)。

```bash
python3 -m unittest discover -s scripts -p test_monitoring.py -v
python3 scripts/monitoring.py check
python3 scripts/verify-monitoring.py
```

隔离验收使用随机 Docker 容器、网络和数据卷，本机 SMTP 服务与一次性证书，不读取真实邮箱配置、不发送外部邮件。复用正式指标抓取、告警规则和邮件模板，验证真实规则触发/恢复、中文邮件、STARTTLS 和 SMTP 认证、临时 451 错误后的重试，并验证容器重启后指标与静默保留。正常结束或 Python 异常均清理本次资源；进程被强杀时可按 `koko-monitor-test-` 前缀识别遗留资源。

配置选项依据 [Prometheus 官方配置](https://prometheus.io/docs/prometheus/latest/configuration/) 与 [Alertmanager 官方邮件配置](https://prometheus.io/docs/alerting/latest/configuration/#email_config)。固定镜像版本以实际配置和测试为准，后续升级需重新验收。
