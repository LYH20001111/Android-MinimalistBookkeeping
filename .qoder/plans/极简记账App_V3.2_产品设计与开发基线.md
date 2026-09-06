# 极简记账App_V3.2_产品设计与开发基线

> 承接版本：V3.1（私有云稳定性与服务器管理）  
> 建议版本：V3.2  
> 版本主题：**多账本体系 + 家庭共享基础 + 权限安全 + 服务器可运维性**

---

# 0. V3.1 完成后的总体判断

根据 V3.1 开发计划，当前版本已经完成服务器健康检查、备份恢复、Recovery Epoch、回收站、同步诊断、冲突历史、账号/本地账本绑定、管理员入口和版本收尾，并已完成主要自动化测试；剩余人工验收集中在 PostgreSQL 真环境、双真机、多设备收敛、备份恢复演练、网络异常和 10 万笔性能抽查。fileciteturn5file0L193-L207

V3.1 的产品方向是合理的，因为它补齐了“私有云能运行”的关键闭环：服务器可连接、数据可备份、误删可恢复、同步可诊断、冲突可追踪、账号边界可理解。

下一阶段不建议继续围绕同步细节堆功能，而应解决一个更大的产品结构问题：**账号已经有了边界，但业务数据仍然基本是一套账。**

V3.2 建议正式引入“账本 Ledger”作为业务根节点，把 User、Ledger、Membership、Device 四个概念解耦，然后在此基础上支持家庭共享和更完整的服务器安全治理。

---

# 1. V3.1 仍可优化的地方

## 1.1 账号与账本仍然耦合

V3.1 已经明确当前账号与本地账本的绑定关系，但从长期产品演进看，“一个登录账号 = 一个业务账本”仍会限制个人账本、家庭账本、旅行账本等场景。

建议：

```text
User
 ├── Ledger A
 ├── Ledger B
 └── Ledger C
```

而不是继续：

```text
User
 └── 全部业务数据
```

## 1.2 同步应从“用户级”升级为“账本级”

V3.1 已经强调游标按账号隔离，并支持 recovery epoch。下一步应把隔离单位进一步收紧到 Ledger：

```text
User → Ledger → Business Data
```

Push/Pull、cursor、权限、回收站、搜索、预算都必须建立在当前 Ledger 上。

## 1.3 管理员模型可以更正式

V3.1 规定 `admin-emails` 优先，否则最早注册账号作为管理员；这很适合家庭服务器开箱即用，但不适合长期运营。fileciteturn5file0L48-L61

后续应升级为：

```text
SERVER_OWNER
SERVER_ADMIN
NORMAL_USER
```

并支持管理员转移与管理操作审计。

## 1.4 公开健康接口需要分级

V3.1 健康接口包含服务器版本、API/协议版本、数据库、磁盘、epoch、最近备份等字段。fileciteturn5file0L79-L91

局域网环境可以接受，但一旦通过公网暴露，会增加信息泄漏面。

建议拆成：

- Public Health：只回答是否在线。
- User Health：版本、协议、服务器时间、epoch。
- Admin Health：磁盘、数据库、备份、统计等详细信息。

## 1.5 JSON 业务备份不等于数据库灾难备份

V3.1 已冻结备份为业务表 JSON，且不包含 refresh token 和邮件验证 token。fileciteturn5file0L58-L62

这适合业务级恢复与迁移，但不等于 PostgreSQL 完整灾难恢复。

V3.2 建议同时明确：

```text
Business Backup = JSON
Disaster Backup = PostgreSQL dump
```

## 1.6 长耗时备份/恢复可继续优化为 Job

V3.1 的恢复已有写屏障和事务语义。fileciteturn5file0L94-L112

下一步更推荐：

```text
POST restore
→ jobId
→ RUNNING
→ SUCCESS / FAILED
```

避免大数据库恢复被 HTTP timeout 打断。

## 1.7 还缺真正的“数据生命周期”体系

V3.1 已经有软删除和回收站，但目前业务层主要解决“找回”。

V3.2 应统一定义：

```text
ACTIVE
ARCHIVED
DELETED
PURGED
```

普通用户只接触前三种；物理清理由管理员维护能力控制。

---

# 2. V3.2 产品定位

> **从“我在多个设备上记同一本账”，升级为“我可以管理多本账，并安全地与家人共享”。**

核心价值不在于增加页面，而在于建立长期可扩展的数据结构。

## 2.1 目标用户

P0：个人用户，多账本。  
P1：家庭用户，多成员共享。  
P2：室友、旅行、项目等小型共享场景。

## 2.2 本版本目标

1. 多账本。
2. 账本级同步与隔离。
3. 共享账本。
4. 成员与角色。
5. 设备管理。
6. 管理员角色。
7. 管理审计。
8. 更强的备份/恢复能力。
9. 数据迁移兼容。
10. 为 V4 AI 和高级分析建立数据根节点。

## 2.3 明确不做

- OCR。
- AI 自动分类。
- 银行流水自动抓取。
- 实时 WebSocket。
- 企业级复杂 RBAC。
- 公有云多活。
- 手机触发服务器恢复。
- 自动清除回收站数据。

---

# 3. 核心数据模型

## 3.1 User

```text
id
email
password_hash
email_verified
status
created_at
updated_at
```

## 3.2 Ledger

```text
id
sync_id
name
description
currency
owner_user_id
is_archived
version
is_deleted
deleted_at
created_at
updated_at
```

Ledger 是所有业务数据的根节点。

## 3.3 Membership

```text
id
ledger_id
user_id
role
status
invited_by
invited_at
accepted_at
created_at
updated_at
```

角色：

```text
OWNER
ADMIN
MEMBER
VIEWER
```

## 3.4 Device

V3.1 Device 模型继续保留，但增加：

```text
device_name
platform
app_version
last_seen_at
last_sync_at
is_revoked
```

Device 属于 User，不直接属于 Ledger；一个用户可以在多个账本间切换。

---

# 4. 业务表升级

以下五张表全部增加：

```text
ledger_id
```

- transaction
- category
- account
- budget
- recurring_transaction

所有服务端查询必须带当前 Ledger 条件，并同时校验当前用户是否有 Membership。

禁止只按 `user_id` 判断业务访问权限。

---

# 5. V3.1 → V3.2 数据迁移

## 5.1 旧数据自动生成默认账本

每个已有 User 自动创建：

```text
我的账本
```

并创建：

```text
Membership = OWNER
```

旧的五类业务数据全部回填该 `ledger_id`。

## 5.2 保持 syncId

迁移过程中不得重生成现有业务对象的 `sync_id`，避免设备侧产生大量“新对象”。

## 5.3 Room

建议：

```text
Room 6 → 7
```

新增：

```text
ledger
membership
local_ledger_state
```

现有业务表增加 `ledger_id`。

## 5.4 Flyway

继续保持：

- PostgreSQL 为 Release 正式数据库。
- H2 只做快速测试。
- 不修改已经执行过的 migration。

---

# 6. 账本管理

## 6.1 我的账本

页面：

```text
我的账本
--------------------
✓ 个人账本
  家庭账本
  旅行账本
--------------------
+ 新建账本
+ 加入账本
```

## 6.2 创建账本

字段：

```text
账本名称
描述（可选）
货币
```

V3.2 只支持单账本单一主币种，不做多币种计算。

## 6.3 切换账本

切换时必须：

1. 清空旧账本页面状态。
2. 设置 currentLedgerId。
3. 重新加载首页、图表、预算、账户、周期账单。
4. 使用独立 cursor 同步。

禁止出现上一账本的数据残留。

---

# 7. 首页设计

保持极简，不把家庭协作做成主入口。

建议：

```text
当前账本：家庭账本 ▼
----------------------
今日支出
¥128.00

[记一笔]

最近记录
...
```

账本切换入口必须明显，但不抢记账主功能。

---

# 8. 共享账本

## 8.1 邀请

流程：

```text
账本设置
→ 成员管理
→ 邀请成员
→ 输入邮箱
→ 发送邀请
```

受邀用户：

```text
我的
→ 邀请
→ 接受
```

## 8.2 邀请规则

默认有效期：7 天。

邀请必须：

- 唯一 invitationId。
- 一次性接受。
- 过期不可接受。
- 允许重新发送。

---

# 9. 成员权限

## OWNER

可以：账本设置、成员管理、角色管理、删除/恢复账本。

## ADMIN

可以：邀请、移除普通成员、修改普通成员角色、账本设置。

不能：转移 OWNER、删除账本。

## MEMBER

默认可以：新增/修改/删除账本交易、查看预算和统计。

为保持产品简单，V3.2 默认允许 Member 编辑账本中的全部交易；以后有精细需求再增加“只能编辑自己的交易”。

## VIEWER

只能查看和统计，不能写入。

---

# 10. 同步协议升级

## 10.1 请求范围

Push/Pull 增加：

```json
{
  "ledgerId": "..."
}
```

服务端依次校验：

1. token 有效。
2. 用户存在。
3. Ledger 存在。
4. 用户是 Ledger 成员。
5. 角色允许当前操作。
6. syncId 属于当前 Ledger。

## 10.2 Cursor

由：

```text
account + cursor
```

升级为：

```text
account + ledger + cursor + recoveryEpoch
```

## 10.3 Pull

Pull 必须只返回当前用户有权限访问的目标 Ledger。

不能用“客户端自己过滤”代替服务端隔离。

---

# 11. 冲突模型

继续保留 V3 的 LWW 和自动收敛，不增加阻断式冲突弹窗。

Conflict Log 增强：

```text
ledger_id
entity_type
sync_id
winner
winner_user_id
winner_device_id
loser_user_id
loser_device_id
base_version
server_version
created_at
```

让系统可以回答：

> 谁的修改覆盖了谁的修改。

---

# 12. 删除与恢复

## 12.1 业务数据

继续使用：

```text
is_deleted
 deleted_at
```

回收站仍然支持：

- 交易。
- 分类。
- 账户。
- 周期账单。

## 12.2 账本删除

OWNER 删除账本：

1. 二次确认。
2. 明确影响所有成员。
3. 软删除 Ledger。
4. 保留业务数据。
5. 进入账本回收站。

只有 OWNER 可以恢复。

---

# 13. 搜索、预算、周期账单

所有功能默认作用于当前 Ledger。

## 搜索

新增：

```text
成员：全部 / 我 / 指定成员
```

## 预算

主键语义：

```text
ledger + category + year + month
```

## 周期账单

必须带 `ledger_id`，生成交易时必须再次校验周期账单属于当前 Ledger。

---

# 14. 管理员模型

V3.1 “最早注册用户自动管理员”的规则仅作为初始化兜底。

V3.2 正式模型：

```text
SERVER_OWNER
SERVER_ADMIN
NORMAL_USER
```

## Server Owner

拥有全部服务器管理能力，可转移 Owner。

## Server Admin

可以健康检查、备份、恢复、查看审计和统计，但不能转移 Owner。

## Normal User

不能访问 `/admin`。

---

# 15. 管理审计

新增：

```text
admin_audit_logs
```

字段：

```text
id
operator_user_id
action
target_type
target_id
ip
user_agent
result
created_at
metadata
```

记录：

- 管理员登录。
- 创建备份。
- 恢复备份。
- 删除备份。
- 修改管理员。
- 禁用用户。
- 撤销设备。

默认保留 180 天。

---

# 16. 用户与设备管理

## 16.1 User Status

```text
ACTIVE
DISABLED
DELETED
```

被禁用用户：

- 不能登录。
- 不能刷新 token。
- 不能同步。
- 本地仍可只读查看已有数据。

客户端必须显示明确的“账号已被服务器禁用”，不能映射为网络异常。

## 16.2 Device Revoke

用户：

```text
我的
→ 同步与安全
→ 已登录设备
```

支持：

```text
注销设备
```

服务端：

```text
is_revoked = true
```

并立即撤销 refresh token。

---

# 17. Health 接口分级

## Public

```text
GET /api/v1/server/health
```

只返回：

```json
{"status":"UP"}
```

## User

返回：

- serverVersion
- apiVersion
- syncProtocolVersion
- serverTime
- recoveryEpoch

## Admin

追加：

- PostgreSQL 状态。
- 磁盘状态。
- inode 状态。
- 最近备份。
- 数据库大小。
- 用户数。
- 账本数。
- 设备数。
- 交易数。

---

# 18. PostgreSQL 灾难备份

继续保留 V3.1 的 JSON Business Backup，同时增加 PostgreSQL Dump 能力。

```text
Business Backup
→ JSON
→ 业务级恢复/迁移

Disaster Backup
→ pg_dump
→ 服务器灾难恢复
```

不得把数据库凭据或 token 写入业务备份。

---

# 19. Backup / Restore Job

新增：

```text
server_jobs
```

字段：

```text
job_id
type
status
created_by
created_at
started_at
finished_at
error_code
message
result_json
```

类型：

```text
BACKUP
RESTORE
DATABASE_CHECK
MAINTENANCE
```

Restore 不再要求单次 HTTP 请求完成全部工作。

---

# 20. 存储与数据库监控

继续 V3.1 水位：

- >20% NORMAL
- 10%~20% WARNING
- <10% CRITICAL

新增监控：

- inode 使用率。
- PostgreSQL version。
- Flyway version。
- DB size。
- active connections。

---

# 21. 服务器管理页

菜单建议：

```text
概览
用户
账本
设备
备份
恢复
任务
审计日志
系统设置
```

概览页面：

```text
运行状态
服务器版本
数据库
磁盘
最近备份
恢复代际
用户数
账本数
设备数
交易数
```

---

# 22. 系统设置

可配置：

- 服务器名称。
- 备份目录。
- 备份计划。
- 备份保留策略。
- 是否允许新用户注册。
- 管理员。

不建议在线修改：

- 数据库连接。
- JWT secret。
- Flyway 配置。

---

# 23. 连接与同步中心

普通用户只看到：

```text
当前账本
服务器
同步状态
待同步
最后同步
冲突
设备
```

状态建议：

```text
已同步
正在同步
等待网络
同步异常
服务器维护中
```

高级诊断继续展示：

- Push/Pull。
- cursor。
- epoch。
- retry。
- event history。

---

# 24. 错误码

新增：

```text
LEDGER_NOT_FOUND
LEDGER_ACCESS_DENIED
LEDGER_ROLE_REQUIRED
LEDGER_DELETED
INVITATION_EXPIRED
INVITATION_ALREADY_USED
USER_DISABLED
DEVICE_REVOKED
RESTORE_IN_PROGRESS
BACKUP_NOT_FOUND
SERVER_MAINTENANCE
```

客户端必须统一映射，禁止把全部服务端失败显示成“网络错误”。

---

# 25. 通知策略

V3.2 不引入复杂推送。

至少支持在下一次同步后提示：

- 收到新账本邀请。
- 被移出账本。
- 角色发生变化。
- 账号被禁用。

---

# 26. 导入导出

CSV 导入必须明确目标：

```text
导入到当前账本
```

禁止出现跨账本不明归属。

CSV/JSON 导出支持：

- 当前账本。
- 全部可访问账本（高级）。

---

# 27. 默认分类与账户

新建 Ledger 时，默认分类和账户应由服务端初始化一次并返回 canonical syncId。

继续使用 V3 已建立的 `mergeInto / applyMergedInto` 思路，避免：

```text
成员 A 创建“现金”
成员 B 创建“现金”
→ 出现两个逻辑相同对象
```

必须测试两成员同时加入并初始化默认数据的情况。

---

# 28. 客户端架构

建议增加：

```text
LedgerRepository
MembershipRepository
DeviceRepository
CurrentLedgerProvider
```

业务 Repository 显式接收：

```text
ledgerId
```

例如：

```java
transactionDao.observeByLedgerId(ledgerId);
```

ViewModel 不直接假设“当前登录用户就是当前账本”。

---

# 29. 客户端状态管理

建议：

```text
CurrentUserProvider
        ↓
CurrentLedgerProvider
        ↓
ViewModel
        ↓
Repository
        ↓
DAO / Sync
```

切换账本必须是显式状态变更。

---

# 30. 性能目标

### 本地

10 万笔交易：

- 首页首屏目标 < 500ms。
- 月度统计目标 < 800ms。
- 搜索目标 < 500ms。

### 同步

10 万笔首次同步：

- 后台执行。
- 分页。
- 批量数据库写入。
- 可中断、可恢复。

### 服务端

单账本 10 万笔交易、100 成员以内应保持正常可用。

---

# 31. Pull 分页

建议演进到：

```json
{
  "items": [],
  "nextCursor": 123,
  "hasMore": true
}
```

`cursor` 仍然是逻辑变更游标，不使用 OFFSET 作为同步游标。

---

# 32. 幂等模型

以下操作必须定义稳定幂等语义：

```text
push = syncId + version
invite = invitationId
restore = jobId
backup = jobId
revokeDevice = deviceId
```

重复请求不应产生重复业务对象。

---

# 33. 数据迁移向导

V3.1 → V3.2 首次打开：

```text
欢迎使用新版账本

原来的账单已经整理为“我的账本”。

[继续]
```

不要把 Room/Flyway 等工程概念暴露给普通用户。

---

# 34. 测试矩阵

## P0 自动化

服务端：

- Ledger 权限。
- Membership。
- Invitation。
- Device revoke。
- Admin role。
- Audit log。
- Backup/Restore job。
- Push/Pull ledger 隔离。

客户端：

- Room 6→7。
- ledger_id 过滤。
- 切换账本。
- payload 映射。
- recovery epoch。
- 多账本 cursor。

## 多设备多成员

至少：

```text
Device A / User A
Device B / User A
Device C / User B
```

测试：

1. 同时新增交易。
2. 同时修改交易。
3. 删除/恢复。
4. 成员被移除。
5. 设备被注销。
6. 账本删除/恢复。
7. 多成员默认数据去重。

---

# 35. 安全测试

必须覆盖：

- 未登录访问 Ledger。
- 非成员访问 Ledger。
- MEMBER 调用 ADMIN API。
- VIEWER 调用写接口。
- 被禁用用户继续同步。
- 被撤销设备继续 refresh。
- 过期邀请重复接受。
- Restore job 重放。
- Backup job 重放。
- 管理页权限绕过。

---

# 36. Release 验收标准

## P0

- 一个账号可拥有多本账。
- 切换账本不串数据。
- 五张业务表完全 Ledger 隔离。
- 多成员共享账本可正常同步。
- OWNER / ADMIN / MEMBER / VIEWER 生效。
- 被移除成员立即失去访问权。
- 被禁用账号无法同步。
- 设备注销立即失效。
- V3.1 升级无数据丢失。

## P1

- 邀请完整。
- 账本回收站完整。
- 管理审计完整。
- 设备管理完整。
- Backup/Restore 支持 Job。

## P2

- PostgreSQL dump。
- Backup encryption。
- Inode 监控。
- 高级导出。

---

# 37. 发布升级流程

正式升级必须：

```text
V3.1 backup
↓
backup validation
↓
Flyway migration
↓
default ledger creation
↓
ledger_id backfill
↓
server startup check
↓
single-device smoke test
↓
dual-device sync test
↓
multi-member test
↓
release
```

migration 失败时禁止自动继续业务写入。

---

# 38. V3.2 开发阶段

## Phase 0：数据模型 P0

Ledger / Membership / ledger_id / Room 6→7 / Flyway。

## Phase 1：账本切换 P0

账本列表、新建、切换、默认账本迁移。

## Phase 2：同步升级 P0

ledgerId / ledger cursor / permission / Push / Pull。

## Phase 3：共享 P0

邀请、成员、角色。

## Phase 4：安全 P1

设备、用户状态、撤销。

## Phase 5：服务器 P1

Server Owner、Admin、Audit、Job。

## Phase 6：灾备 P1/P2

pg_dump、加密、恢复演练。

## Phase 7：回归 P0

PostgreSQL、三设备、多账本、多成员、升级回滚。

---

# 39. V3.2 后续路线

## V3.3：深度分析与协作

候选：

- 成员维度统计。
- 标签。
- 自定义分析维度。
- 账本比较。
- 更丰富的预算分析。

## V4.0：智能记账

候选：

- OCR。
- AI 自动分类。
- 文本账单解析。
- 智能预算提醒。
- 异常消费检测。
- 自然语言记账。

先完成 Ledger 基础，是为了保证未来 AI 能够明确服务于某一本账、某个家庭账本或某个项目账本。

---

# 40. V3.2 最终产品原则

### 原则 1：先账本，再 AI

Ledger 是未来所有智能能力的业务边界。

### 原则 2：权限服务器最终裁决

客户端只能隐藏 UI，不能代替服务端鉴权。

### 原则 3：同步范围最小化

请求只获取当前用户有权限访问的当前账本数据。

### 原则 4：恢复必须可追踪

Backup / Restore / Migration 都应形成 job、结果和审计记录。

### 原则 5：多人协作不能破坏极简体验

复杂能力放到：

```text
账本设置
成员管理
同步与安全
服务器管理
```

而不是塞进“记一笔”主流程。

---

# 41. 五项开发前冻结决策

1. **多币种**：V3.2 不做，单账本单主币种。
2. **Member 编辑权限**：允许编辑全部账本交易，保持简单。
3. **账本删除**：继续软删除，不做普通用户物理删除。
4. **Server Owner 转移**：允许，但必须重新认证。
5. **PostgreSQL dump**：纳入正式灾难恢复能力。

---

# 42. 最终判断

V3.1 已经把“同步功能”推进到了“可维护的私有服务器产品”。下一阶段真正需要解决的是**产品信息架构**而不是继续堆同步功能。

因此推荐版本路线固定为：

```text
V3.1
私有云稳定性 + 备份恢复 + 回收站 + 诊断
        ↓
V3.2
多账本 + 家庭共享 + 权限 + 安全治理
        ↓
V3.3
深度分析 + 协作体验
        ↓
V4.0
AI 智能记账
```

**V3.2 的发布标准不是“增加了多少功能”，而是：用户可以安全拥有多本账、在多个设备使用、邀请家人共享、切换账本而不串数据，同时服务器管理员能够看懂、备份、恢复和审计整个系统。**
