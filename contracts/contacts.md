# 好友与联系人协议（已实现）

当前启用 `local` 时系统信息 `stage=contacts`，包含认证、单聊和好友功能。使用 `Authorization: Bearer <accessToken>`，错误结构沿用 [认证契约](auth.md) 的 `code/message`。所有 ID 均为正 Long 的十进制字符串，时间为 UTC ISO-8601。

## 接口

| 方法与路径 | 请求与返回 |
| --- | --- |
| `POST /api/friend-requests` | `{"account":"bob","greeting":"你好，一起交流"}`；返回申请对象，HTTP 200 |
| `POST /api/friend-requests/{id}/accept` | 无请求体；接收方接受申请，返回已处理申请 |
| `POST /api/friend-requests/{id}/reject` | 无请求体；接收方拒绝申请，返回已处理申请 |
| `GET /api/friend-requests` | `afterId=0&limit=50&direction=all&status=ALL`；返回当前用户的申请分页 |
| `GET /api/friends` | `afterId=0&limit=50`；返回当前用户好友分页 |

账号为 3–32 位英文字母、数字或下划线，不区分大小写；附言可省略，最多 255 个 UTF-16 单元，保存时去除首尾空白。创建申请按已认证用户限流，每 60 秒固定窗口最多 20 次调用，重试也计数。分页 limit 为 1–100。

申请筛选 direction 支持 `all / incoming / outgoing`；status 支持 `ALL / PENDING / ACCEPTED / REJECTED / CANCELLED`。CANCELLED 只保留数据库状态兼容，当前没有取消接口。

申请对象：

```json
{
  "id":"31","senderId":"1","receiverId":"2",
  "senderAccount":"alice","senderNickname":"小可",
  "receiverAccount":"bob","receiverNickname":"小波",
  "greeting":"你好，一起交流","status":"PENDING",
  "createdAt":"2026-09-08T00:00:00Z","handledAt":null
}
```

申请分页：`{"requests":[...],"nextCursor":"31","hasMore":false}`。

好友分页：`{"friends":[{"id":"2","account":"bob","nickname":"小波","remark":null}],"nextCursor":"2","hasMore":false}`。只显示仍为 ACTIVE 的好友；remark 为预留读取字段，本轮未开放修改备注。

分页按数值 ID 升序，后续使用 nextCursor。随机 ID 不代表创建先后，分页也不是一致性快照；分页期间新增的较小 ID 可能在下一轮从 0 开始刷新时出现。桌面端按申请时间倒序展示，待处理优先。

## 处理规则

1. 不允许添加自己；目标账号必须存在且有效。已经是好友时返回 ALREADY_FRIENDS。
2. 同一对用户最多一条 PENDING 申请。同方向重复提交返回原申请，不修改原附言；相反方向重复提交返回 INCOMING_REQUEST_PENDING，由用户明确选择接受或拒绝，不自动互加。
3. 只有申请接收方能处理。申请发起人及第三方尝试处理时均返回 404 REQUEST_NOT_FOUND，不能读取超出权限的详情。
4. 接受申请时，双向 friendship 和申请 ACCEPTED/handledAt 同事务提交；任一写入失败全部回滚。拒绝只更新申请状态，不创建好友关系。
5. 对同一申请重复执行同一决定返回原终态；尝试把已拒绝改为接受或把已接受改为拒绝返回 REQUEST_HANDLED。
6. 拒绝后允许发起新申请，新记录有新 ID。创建操作仅对当前待处理申请去重，不是跨申请历史的全局幂等键。
7. 发申请与处理申请统一按较小用户 ID、较大用户 ID 的顺序锁用户行，再锁申请行。使用 READ COMMITTED 和数据库 pending_pair 唯一约束，避免双方并发互加的反向死锁与重复关系。

## 错误码

| HTTP / code | 含义 |
| --- | --- |
| 400 INVALID_REQUEST / SELF_REQUEST | 输入、分页或筛选参数错误 / 添加自己 |
| 401 UNAUTHENTICATED | 当前身份无效 |
| 404 USER_NOT_FOUND / REQUEST_NOT_FOUND | 目标不可添加 / 申请不存在或无权处理 |
| 409 ALREADY_FRIENDS | 已有好友关系 |
| 409 INCOMING_REQUEST_PENDING | 对方已发起待处理申请 |
| 409 REQUEST_HANDLED | 申请已进入不同终态 |
| 429 RATE_LIMITED | 达到申请调用限额 |
| 503 BUSY / SERVICE_UNAVAILABLE | 事务或存储暂不可用，刷新后重试 |

客户端收到明确成功响应才清空输入并显示成功；响应丢失时显示“结果暂未确认”，通过刷新核对状态。同步和修改串行执行，防止旧列表覆盖新决定。联系人资料只保存在当前账号的内存中，每 10 秒分页刷新，也支持手动刷新；本轮没有好友变更 MQ 推送或离线联系人库。

好友关系用于联系人管理及快捷聊天入口。本轮保留 [单聊契约](chat.md) 中按准确账号直接聊天的兼容能力，尚未将“必须互为好友”设为发言条件。删除好友、取消申请、拉黑、备注编辑和群聊不在本轮实现范围。
