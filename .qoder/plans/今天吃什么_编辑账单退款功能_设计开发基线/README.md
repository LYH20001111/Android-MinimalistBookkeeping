# 「今天吃什么」/ 账单模块：编辑账单退款功能设计与开发基线

> 版本：V1.0  
> 平台：Android  
> 技术：Kotlin + Jetpack Compose + Room  
> 本次变更：在现有“编辑账单”页右上角 `⋮` 更多菜单中增加“退款”。

---

## 1. 需求背景

当前“编辑账单”页面右上角已有 `⋮` 更多操作入口，现有功能主要为“编辑记录”。

本次不新增独立的大号退款按钮，而是统一调整为：

```text
编辑账单
      ↓
右上角 ⋮
      ↓
更多操作
├── 编辑记录
└── 退款
```

原因：

- 保持页面主体简洁；
- “编辑记录”和“退款”均属于当前账单级操作；
- 退款属于资金操作，适合放在二级菜单；
- 降低误触。

---

## 2. 页面定位

现有页面主体保持不变：

```text
┌────────────────────────────┐
│ ←  编辑账单               ⋮ │
├────────────────────────────┤
│ 支出 | 收入 | 转账          │
│                            │
│          ¥11.88            │
│ 金额                       │
│ [11.88]                    │
│                            │
│ 选择分类                   │
│ [餐饮] [交通] [购物] ...   │
│                            │
│ 账户                       │
│ [信用卡                ▼]  │
│                            │
│ 日期 / 时间                │
└────────────────────────────┘
```

本次主要影响：

```text
右上角 ⋮
```

和其后的退款流程。

---

## 3. 更多菜单

### 入口

使用：

```kotlin
Icons.Outlined.MoreVert
```

建议：

- 图标：24dp
- 点击区域：48dp × 48dp
- 位于 TopAppBar 最右侧

### 菜单

```text
┌──────────────────┐
│ 编辑记录          │
│ 退款              │
└──────────────────┘
```

建议：

- `DropdownMenu`
- `DropdownMenuItem`
- 宽度约 160～200dp
- 白色背景
- 圆角 12～16dp
- 轻阴影
- 不使用大面积红色

---

## 4. 菜单显示规则

| 账单状态 | 更多菜单 |
|---|---|
| 待支付 | 编辑记录 |
| 已支付 | 编辑记录 + 退款 |
| 退款处理中 | 编辑记录 |
| 部分退款且仍可退 | 编辑记录 + 退款 |
| 已退款 | 编辑记录 |
| 退款失败 | 编辑记录 + 重新退款 |
| 已取消 | 编辑记录 |

退款显示条件：

```text
canRefund == true
```

其中业务层计算：

```text
refundableAmount > 0
```

---

## 5. 退款主流程

```text
编辑账单
 ↓
⋮
 ↓
退款
 ↓
退款确认 BottomSheet
 ↓
选择退款金额
 ↓
选择退款原因
 ↓
确认退款
 ↓
退款处理中
 ↓
退款结果
```

不要在点击“退款”菜单项后直接完成退款。

---

## 6. 退款 BottomSheet

建议采用 `ModalBottomSheet`：

```text
┌────────────────────────────┐
│        申请退款             │
│                            │
│ 原始金额                   │
│ ¥128.00                    │
│                            │
│ 可退款金额                 │
│ ¥128.00                    │
│                            │
│ 退款金额                   │
│ [ ¥128.00 ]                │
│                            │
│ 退款原因                   │
│ ○ 不需要了                 │
│ ○ 重复支付                 │
│ ○ 商品/服务问题            │
│ ○ 其他                     │
│                            │
│ [取消]          [确认退款]  │
└────────────────────────────┘
```

---

## 7. 全额退款

例如：

```text
原始金额：¥11.88
退款金额：¥11.88
```

退款完成：

```text
状态：已退款
累计退款：¥11.88
实际支付：¥0.00
```

---

## 8. 部分退款

例如：

```text
原始金额：¥11.88
退款金额：¥5.00
```

完成后：

```text
状态：部分退款
原始金额：¥11.88
累计退款：¥5.00
实际支付：¥6.88
```

---

## 9. 退款状态模型

```kotlin
enum class RefundStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    CANCELLED
}
```

账单状态：

```kotlin
enum class BillStatus {
    PENDING,
    PAID,
    REFUND_PROCESSING,
    PARTIALLY_REFUNDED,
    REFUNDED,
    REFUND_FAILED,
    CANCELLED
}
```

---

## 10. 数据库设计

核心关系：

```text
Bill
 ├── BillItem
 └── RefundRecord
```

关系：

```text
Bill 1 ───── N RefundRecord
```

### Bill

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | Room 主键 |
| uuid | String | 全局唯一 ID |
| originalAmount | Long | 原始金额，单位分 |
| paidAmount | Long | 已支付金额，单位分 |
| refundedAmount | Long | 累计退款金额，单位分 |
| status | String | 账单状态 |
| paymentMethod | String? | 支付方式 |
| paidAt | Long? | 支付时间 |
| createdAt | Long | 创建时间 |
| updatedAt | Long | 修改时间 |

### RefundRecord

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | Room 主键 |
| uuid | String | 退款记录 UUID |
| billId | Long | 所属账单 |
| amount | Long | 本次退款金额，单位分 |
| status | String | 退款状态 |
| reason | String? | 退款原因 |
| requestedAt | Long | 发起时间 |
| processedAt | Long? | 完成时间 |
| failureReason | String? | 失败原因 |
| transactionNo | String? | 退款流水号 |
| createdAt | Long | 创建时间 |
| updatedAt | Long | 更新时间 |

---

## 11. 金额规则

金额不要用 `Double` 作为最终存储。

使用：

```text
Long
```

单位：

```text
分
```

例如：

```text
¥11.88 → 1188
```

可退款金额：

```text
refundableAmount =
originalAmount - refundedAmount
```

必须满足：

```text
refundAmount > 0
refundAmount <= refundableAmount
```

---

## 12. 为什么必须独立 RefundRecord

不能只在账单中增加：

```text
refunded = true
```

也不建议只保存：

```text
refundAmount
```

因为以后需要知道：

- 退了几次；
- 每次退多少；
- 什么时候退；
- 为什么退；
- 是否成功；
- 退款流水号；
- 失败原因。

因此：

```text
Bill
  ↓
RefundRecord #1
RefundRecord #2
RefundRecord #3
```

---

## 13. 账单列表展示

列表显示当前最终状态，但保留原始金额。

### 正常

```text
餐饮
2026-09-19 18:30

¥128.00       已支付
```

### 部分退款

```text
餐饮
2026-09-19 18:30

¥128.00       部分退款
```

### 已退款

```text
餐饮
2026-09-19 18:30

¥128.00       已退款
```

### 退款处理中

```text
餐饮
2026-09-19 18:30

¥128.00       退款处理中
```

不要在列表中把原账单金额直接改成退款金额。

---

## 14. 账单详情

推荐：

```text
金额明细
────────────────
原始金额        ¥128.00
累计退款         ¥50.00
实际支付         ¥78.00

退款记录
────────────────
¥30.00
已退款
09-19 14:30

¥20.00
已退款
09-19 16:20
```

多次退款可以用时间线展示。

---

## 15. 退款状态颜色

建议：

```text
已支付       Primary / 中性色
退款处理中   Warning
部分退款     Primary / 灰紫
已退款       Success
退款失败     Danger
```

推荐：

```text
Primary = #8068E8
Warning = #F2A65A
Success = #70B79B
Danger  = #E96A7B
```

退款本身不要默认使用红色 Primary。

红色用于：

- 失败；
- 异常；
- 危险操作。

---

## 16. 退款失败

失败记录不能删除。

示例：

```text
退款失败

退款金额：¥50.00

原因：
支付渠道暂时无法处理

[重新申请退款]
```

重新申请应创建新的 `RefundRecord`，而不是覆盖旧失败记录。

---

## 17. 退款处理中

提交成功后：

```text
BillStatus = REFUND_PROCESSING
```

当前退款操作禁止重复发起。

UI：

```text
¥128.00

退款处理中
```

菜单只保留：

```text
编辑记录
```

---

## 18. 部分退款状态

计算：

```text
refundedAmount == 0
→ PAID

0 < refundedAmount < originalAmount
→ PARTIALLY_REFUNDED

refundedAmount == originalAmount
→ REFUNDED
```

---

## 19. 退款 UI 状态

```text
PAID
    ↓
点击退款
    ↓
REFUND_PROCESSING
    ↓
┌─────────────┐
│             │
成功          失败
│             │
↓             ↓
PARTIAL/FULL  FAILED
              ↓
             重试
```

---

## 20. Kotlin 页面结构

```text
BillEditScreen
│
├── TopAppBar
│   ├── BackButton
│   ├── Title
│   └── MoreButton
│
├── BillEditContent
│
└── BillMoreMenu
    ├── EditRecord
    └── Refund
          ↓
RefundBottomSheet
    ├── RefundAmount
    ├── RefundReason
    ├── CancelButton
    └── ConfirmRefundButton
```

---

## 21. Compose 更多菜单

```kotlin
var showMenu by remember {
    mutableStateOf(false)
}

IconButton(
    onClick = {
        showMenu = true
    }
) {
    Icon(
        imageVector = Icons.Outlined.MoreVert,
        contentDescription = "更多操作"
    )
}

DropdownMenu(
    expanded = showMenu,
    onDismissRequest = {
        showMenu = false
    }
) {
    DropdownMenuItem(
        text = {
            Text("编辑记录")
        },
        onClick = {
            showMenu = false
            onEditRecord()
        }
    )

    if (bill.canRefund) {
        DropdownMenuItem(
            text = {
                Text(
                    if (bill.status == BillStatus.REFUND_FAILED)
                        "重新退款"
                    else
                        "退款"
                )
            },
            onClick = {
                showMenu = false
                onRefund()
            }
        )
    }
}
```

---

## 22. canRefund 建议由业务层生成

不要让 Composable 中塞大量状态判断。

建议：

```kotlin
data class BillDetailUiModel(
    val id: Long,
    val amount: Long,
    val status: BillStatus,
    val canRefund: Boolean
)
```

UI 只关心：

```kotlin
if (bill.canRefund) {
    ...
}
```

---

## 23. UseCase

建议：

```text
GetBillDetailUseCase
RequestRefundUseCase
RetryRefundUseCase
GetRefundHistoryUseCase
```

### RequestRefundUseCase

负责：

```text
检查账单状态
↓
计算可退款金额
↓
校验退款金额
↓
创建 RefundRecord
↓
执行退款
↓
更新 Bill
```

---

## 24. Room Transaction

退款成功后：

```text
RefundRecord
+
Bill.refundedAmount
+
Bill.status
```

应保持一致。

本地数据库更新建议：

```kotlin
database.withTransaction {
    ...
}
```

---

## 25. 后端支付预留

如果以后接入真正支付渠道，可以设计：

```http
POST /bills/{billId}/refunds
```

请求：

```json
{
  "amount": 5000,
  "reason": "不需要了",
  "requestId": "uuid"
}
```

`requestId` 用于幂等，避免网络超时导致重复退款。

---

## 26. V1 开发边界

### 必须

- [ ] 右上角 `⋮`
- [ ] 更多菜单
- [ ] 编辑记录
- [ ] 退款
- [ ] 退款确认
- [ ] 全额退款
- [ ] 部分退款
- [ ] 退款处理中
- [ ] 退款成功
- [ ] 退款失败
- [ ] RefundRecord
- [ ] 退款历史
- [ ] 原始账单保留

### 暂缓

- [ ] 复杂退款审批
- [ ] 多支付渠道
- [ ] 客服退款
- [ ] 智能合并
- [ ] 云端对账
- [ ] 自动退款策略

---

## 27. 验收标准

### UI

- [ ] `⋮` 位于右上角
- [ ] 点击显示“编辑记录”
- [ ] 可退款时显示“退款”
- [ ] 退款失败显示“重新退款”
- [ ] 已退款时不显示退款入口
- [ ] 退款菜单不遮挡主要内容
- [ ] 退款不是大号红色按钮

### 业务

- [ ] 原账单不删除
- [ ] 支持全额退款
- [ ] 支持部分退款
- [ ] 金额不能超过可退款金额
- [ ] 退款处理中不能重复发起
- [ ] 退款失败可以重试
- [ ] 多次退款可追踪

### 数据

- [ ] RefundRecord 独立表
- [ ] 金额用 Long + 分
- [ ] UUID
- [ ] 状态计算正确
- [ ] Room Transaction

---

## 28. 最终交互结构

```text
编辑账单
│
└── ⋮
    │
    ├── 编辑记录
    │
    └── 退款
         ↓
      申请退款
         │
         ├── 退款金额
         ├── 退款原因
         └── 确认
                ↓
          退款处理中
                ↓
       ┌────────┴────────┐
       ↓                 ↓
      成功                失败
       ↓                 ↓
  部分退款/已退款       重新退款
```

---

## 29. 最终设计原则

> **不改变现有编辑账单页面主体布局，把退款统一收纳到右上角 `⋮` 更多操作中；原账单始终保留，退款作为独立的 `RefundRecord` 记录，并通过账单状态反映当前资金状态。**

最终用户体验：

```text
编辑账单
→ 右上角 ⋮
→ 退款
→ 确认退款
→ 退款处理中
→ 已退款 / 部分退款 / 退款失败
```

这个设计既满足当前需求，也为后续多次退款、退款流水、支付渠道、云同步与对账留下扩展空间。
