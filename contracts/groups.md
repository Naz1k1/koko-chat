# 群聊协议（已实现）

`local` 配置的系统阶段为 `read-receipts`，包含认证、好友、单聊及最多 200 人的小群。群管理使用 Bearer 访问令牌与 HTTP，文本收发继续使用 [聊天协议](chat.md) 的 SEND / SEND_ACK / MESSAGE / RECEIVED_ACK / READ。

## 管理接口

| 接口 | 请求体 / 返回 |
| --- | --- |
| `POST /api/groups` | `{clientCommandId,title,memberIds}`；建群，可先建立只有群主的群，再邀请好友 |
| `GET /api/groups/{id}` | 返回群详情及全部有效成员，规模受 200 人上限约束 |
| `POST /api/groups/{id}/members` | `{clientCommandId,membershipEpoch,memberIds}`；群主邀请自己的好友 |
| `POST /api/groups/{id}/members/{userId}/remove` | `{clientCommandId,membershipEpoch,targetEpoch}`；群主移除指定周期的成员 |
| `POST /api/groups/{id}/leave` | `{clientCommandId,membershipEpoch}`；普通成员退出 |
| `POST /api/groups/{id}/close` | `{clientCommandId,membershipEpoch}`；群主解散群 |

修改成功统一返回 HTTP 200：`{"groupId":"20","clientCommandId":"固定操作编号"}`。创建者自动成为 OWNER；普通成员角色为 MEMBER，本轮不开放 ADMIN、转让群主或改群名。群主不能直接退出或移除自己，可以解散群。只校验邀请人与目标互为好友，不要求群内所有人互为好友。

群名称去除首尾空白后不能为空，输入最多 128 个 UTF-16 单元。memberIds 为正 Long 十进制字符串数组；创建最多 199 个，邀请 1–199 个。重复 ID 规范化去重并按数值排序；邀请已在群内的用户是无修改操作。实际有效成员总数（含群主）不得超过 200，并发邀请在会话行锁内重新计数。

群详情示例：

```json
{
  "id":"20","title":"Kotlin 学习小组","ownerId":"1",
  "membershipEpoch":"e156745d-791c-4656-95de-c66621948115",
  "members":[
    {"userId":"1","account":"alice","nickname":"小可","role":"OWNER","membershipEpoch":"e156745d-791c-4656-95de-c66621948115"},
    {"userId":"2","account":"bob","nickname":"小波","role":"MEMBER","membershipEpoch":"9ee3946a-e547-4abf-9665-a20cfacb6cfa"}
  ]
}
```

外层 membershipEpoch 是当前调用者的周期；移除成员时 targetEpoch 必须等于目标成员在详情中的周期。该条件防止界面里的旧成员记录误操作已重新加入的新周期。

## 命令去重

每个修改请求都有 1–64 位字母、数字、下划线或连字符组成的 clientCommandId，推荐 UUID。一次操作在网络超时、5xx 或确认丢失后重试时，必须保留原编号和所有参数。

V3 的 group_command 保存 `(user_id,client_command_id)` 唯一记录、规范化请求摘要及 groupId，与群/成员修改同事务提交。并发重复执行仅一笔事务提交；原编号搭配不同操作、群或参数返回 409 COMMAND_CONFLICT。失败并回滚的操作不会留下成功记录。

去重查询在当前成员权限检查之前，仍须通过当前认证。因此成功退出、解散或移除后的旧命令可以返回原结果，而不会再次产生副作用，也不会返回新的群详情。被移除者重新入群后，重放原移除命令不再移除新周期；使用新命令编号配旧 targetEpoch 会被拒绝。

桌面在当前账号会话中保留未确认操作，显示“重试上次操作”；新操作与重试不能并发提交。该待确认管理操作目前只在内存，关闭应用或注销后不自动恢复；重开后应先刷新会话核对结果。聊天消息发送意图仍使用 SQLite 持久化，与群管理操作是不同生命周期。

## 消息、加入与退出

群和单聊共享 conversation / conversation_member / message / message_outbox。会话列表和 `GET /api/conversations/{id}` 新增 `type` 与 `ownerId`；群的 peerId/account 为 null，nickname 字段沿用展示名职责，值为群名称。单聊的 type 为 DIRECT、ownerId 为 null。历史与发送接口不另建群专用版本。

邀请、移除、退出、解散与 SEND、历史分页和设备回执均在相应操作中获取同一会话行锁。新加入成员的 joinSeq 为当时 latestSeq+1，重新加入时分配全新 membershipEpoch，lastReadSeq 初始化为 joinSeq-1。用户只能获取本周期可见消息，不能把旧周期回执用于新周期。

RabbitMQ 分发消费者读取当前有效成员，按 joinSeq 过滤消息后，向每个成员的在线设备发布网关任务；网关投递前再次核对认证会话、当前成员周期和可见起点。队列任务可重复，客户端仍按消息 ID 和会话/周期/seq 去重，补齐连续序号后再发送 RECEIVED_ACK。成员变更本身暂不作为 MQ 事件推送，元数据通过周期 HTTP 刷新。

退出或被移除后，服务端拒绝发送、读取历史和提交回执。解散标记群为 CLOSED、所有有效成员为 REMOVED，不物理删除消息。客户端下一次成功核验后隐藏会话并清除收到的消息缓存；未成功发送的意图保留为失败状态。网络异常不会被解释为退群。重新加入按新周期重建缓存。已交付的消息和正在传输的帧不具备远程撤回能力。

## 主要错误

| HTTP / code | 含义 |
| --- | --- |
| 400 INVALID_REQUEST | 群名、编号、成员或周期参数错误 |
| 401 UNAUTHENTICATED | 身份失效 |
| 403 FRIEND_REQUIRED / OWNER_REQUIRED | 不是自己的有效好友 / 缺少群主权限 |
| 403 NOT_A_MEMBER | 已不在有效会话中 |
| 404 GROUP_NOT_FOUND | 群不存在或已解散 |
| 409 GROUP_FULL / OWNER_CANNOT_LEAVE | 人数超限 / 群主不能直接退出 |
| 409 MEMBERSHIP_CHANGED | 调用者或目标周期过期 |
| 409 COMMAND_CONFLICT | 成功命令编号被用于不同参数 |
| 503 BUSY / SERVICE_UNAVAILABLE | 暂不可用，保留原编号重试 |

群消息区分服务端保存、设备接收和用户已读。群成员维护各自读进度与未读数，跨设备共享；不显示“所有人已读”。群公告、文件和语音消息尚未实现。
