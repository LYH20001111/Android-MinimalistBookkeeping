# 极简记账 V2 开发计划（账户 + 资金流 + 搜索 + 数据安全 + 预算控制）

## 总体策略
- 范围：全量 V2（P0 + P1）；架构：新增 `domain/usecase` 层，ViewModel 调 UseCase 再调 Repository。
- 数据库一次性升级 `version 2 → 3`，所有 schema 变更集中在单个 `MIGRATION_2_3`（避免多次升版）。
- 保持不变：金额一律 `long`（分）、业务日期为本地当天 00:00 的 epoch millis（Risk E 明确延后）、无 INTERNET 权限、三 Tab 导航。
- 无新增运行时第三方依赖：CSV 手写解析/格式化，备份用内置 `org.json`，文件读写用 SAF（`ActivityResultContracts.OpenDocument/CreateDocument`）。仅新增测试依赖 `androidx.room:room-testing`。
- 交付顺序：先把本计划落地为 `.qoder/plans/极简记账_V2_开发_<id>.md`，再按 Phase 0 → 10 顺序实现，每个 Phase 结束跑一次 `:app:assembleDebug` + `:app:testDebugUnitTest`。

## 关键数据模型决策（先定义清楚，后续所有 Phase 依赖）
1. 交易类型新增 `TYPE_TRANSFER = 3`，常量放在 `CategoryEntity` 现有 `TYPE_EXPENSE/TYPE_INCOME` 旁，沿用既有约定。
2. `transactions` 增加 `account_id`、`transfer_account_id`（均可空，FK → `account(id)` `ON DELETE RESTRICT`）；`category_id` 由 `NOT NULL` 改为可空，转账时为 `NULL`（SQLite 对 NULL 子键不做 FK 校验，安全）。
3. 账户余额统一"可正可负"模型：`balance = initial_balance + 收入 - 支出 + 转入 - 转出`；信用卡欠款即负余额，不单独设计信用账务。
4. `account` 表含 `balance` 缓存列。写入交易时在**同一 DB 事务**内重算并更新受影响账户缓存；`CalculateAccountBalanceUseCase` 从交易重算，作为唯一真值来源与开发期校验依据（缓存 != 重算时以重算纠正）。
5. `budget` 增加 `category_id INTEGER NOT NULL DEFAULT 0`（`0` = 总预算哨兵）。原因：SQLite 唯一索引把 NULL 视为互不相等，用哨兵 0 才能保证 `(year, month, category_id)` 唯一、每月总预算仅一条；budget 无 FK，category_id 恒 >= 1 为分类预算。
6. 统计口径：所有按 type 过滤的查询/纯函数需显式排除转账。`StatisticsCalculator.summary/groupByDay/categoryBreakdown/dailyTrend/monthlyTrend` 遇 `type==3` 既不计收入也不计支出。
7. 账户与分类被历史账单引用后禁止物理删除，只能归档（`is_archived`）；账户删除守卫放在 Repository 层，与现有分类删除守卫同风格。
8. 历史账单迁移后 `account_id` 保持 `NULL`（早于账户体系），不归属任何账户、不影响任何账户余额（假设项，见末尾）。

## 架构分层（domain/usecase）
新增包 `com.skyanchor.bookkeeping.domain`，全部为纯 Java 类，构造注入 `BookkeepingRepository`（读多者可直接注入 DAO 结果）：
```
domain/
├── account/CalculateAccountBalanceUseCase.java   余额重算（初始+收-支+转入-转出）
├── account/AccountBalanceValidator.java          缓存 vs 重算一致性校验/纠正
├── transaction/SearchTransactionsUseCase.java    组装 SearchFilter → DAO 查询 → 结果+统计
├── transaction/TransferValidator.java            转账校验（两账户必填且不同）
├── budget/CalculateBudgetUseCase.java            总预算+分类预算使用率/状态
├── recurring/GenerateRecurringTransactionsUseCase.java  到期待确认账单生成（幂等）
├── importexport/ExportTransactionsUseCase.java
├── importexport/ImportTransactionsUseCase.java
├── importexport/CsvFormatter.java / CsvParser.java
├── importexport/BackupSerializer.java / RestoreUseCase.java
```
`BookkeepingApp` 作为组合根：`getRepository()` 之外新增 `getUseCase(...)`/持有单例 UseCase，供 ViewModel 通过 `AndroidViewModel.getApplication()` 取得。ViewModel 不直接写业务规则，只做状态编排。

---

## Phase 0：V1.1 隐患修复（Step 1）
- Risk A 周序号歧义：`util/DateLabels.periodTitle()` WEEK 分支输出含年份的"2026年第N周"；周期身份继续用 start/end（已是）。
- Risk B Dialog 回调丢失：`CalendarSummaryDialog` 改用 FragmentResult API —— `setFragmentResult("calendar_date_selected", bundle(dayMillis))`；`RecordFragment` 用 `setFragmentResultListener` 接收后调 `viewModel.setBusinessDate()`；移除 `OnDateSelectedListener` 匿名回调接口。
- Risk C 周期选择器全量扫描：`TransactionDao` 新增有界聚合查询（按可见窗口或 `countByWeek/Month/Year(start,end)`）；`ChartViewModel` 的 `week/month/yearOptions` 改用有界查询，不再全量 `observeDayCounts()`。
- Risk D 日历格密度：`item_calendar_day.xml` 抽出独立尺寸 Token（新增 `dimens.xml`：`calendar_cell_min_height`、`calendar_cell_amount_text`），金额单行 + autosize，字体放大不重叠。
- Risk E 业务日期迁移：本轮明确延后，保持 epoch millis（文档允许）。
- 测试：更新 `DateUtilTest`（周标题含年份）、新增 FragmentResult 相关不回归验证。

## Phase 1：数据层地基 —— 账户 + 交易模型升级 + DB v3（Steps 2-3）
新增实体/DAO：
- `data/entity/AccountEntity.java`：`id, name, type(int), initial_balance(long), balance(long 缓存), is_credit(bool), sort_order(int), is_archived(bool), created_at, updated_at`；账户类型常量 `TYPE_CASH/WECHAT/ALIPAY/DEBIT/CREDIT/OTHER`。
- `data/database/AccountDao.java`：`observeAll()`（sort_order 升序）、`observeActive()`（未归档）、`getById`、`insert/update/updateAll`、`archiveById`、`countTransactionsByAccount(id)`（删除守卫）、`observeAccountBalances()`（联表重算投影，供图表/账户页 LiveData 自动刷新）。
- `data/entity/RecurringTransactionEntity.java` + `RecurringTransactionDao.java`（schema 本轮建好，逻辑 Phase 8 用）。
修改：
- `TransactionEntity`：`+account_id +transfer_account_id`（可空 FK→account RESTRICT，加索引）；`category_id` 改可空。
- `TransactionItem`：`+accountId +accountName +transferAccountId +transferAccountName`；`ITEM_COLUMNS/ITEM_FROM` 增加 `LEFT JOIN account a ON t.account_id=a.id LEFT JOIN account ta ON t.transfer_account_id=ta.id`，`categoryId` 用 `COALESCE`。
- `BudgetEntity`：`+category_id NOT NULL DEFAULT 0`；唯一索引改 `(year, month, category_id)`。
- `AppDatabase`：`version=3`；`entities += AccountEntity, RecurringTransactionEntity`；`exportSchema=true` + 配置 schema 目录；新增 `MIGRATION_2_3`（建 account 表并播种默认账户 → 重建 transactions 表加列/改可空/加 FK+索引 → 重建 budget 表加 category_id 哨兵与新唯一索引 → 建 recurring_transaction 表；全程 `PRAGMA foreign_keys=OFF/ON` 包裹，禁用 destructiveMigration）；`SEED_CALLBACK.onCreate` 追加播种默认账户。
- `DefaultData`：`+defaultAccounts()`（现金/微信/支付宝/储蓄卡/信用卡/其他，信用卡 `is_credit=true`）；`clearAllData` 重置时一并重建默认账户。
- `BookkeepingRepository`：账户读写（`saveAccount/archiveAccount/deleteAccount(守卫)/observeAccounts/observeAccountBalances`）；`saveTransaction/deleteTransaction` 改为在事务内触发受影响账户余额重算（调用 `CalculateAccountBalanceUseCase`）。
- `StatisticsCalculator`：全部聚合函数排除 `type==3`。
- `build.gradle.kts` + `libs.versions.toml`：新增 `room-testing`（androidTest），配置 Room schema 导出目录。
- 测试：`AccountBalanceCalculatorTest`（收/支/转入/转出/信用卡负余额/初始余额）、`StatisticsCalculatorTest` 补转账排除用例、`MigrationTest`（见 Phase 10）。

## Phase 2：账户体系 UI + 记录页账户选择（Step 4）
- 新增 `ui/account/AccountManageActivity.java` + `AccountViewModel`（复用 `CategoryManageActivity` 的列表/新增/编辑/归档/删除守卫交互范式）、`ui/adapter/AccountAdapter.java`、`res/layout/activity_account_manage.xml`、`dialog_account_edit.xml`、`item_account_row.xml`（账户卡片：名称 + 类型 + 余额，可正可负用 danger 色）。
- `TransactionEditActivity` + `TransactionEditViewModel`：支出/收入新增"账户"选择器（必填，默认落到首个未归档账户）；`activity_transaction_edit.xml` 增加账户选择区；无账户时提示先去创建。
- `MineFragment` + `fragment_mine.xml`：新增"账户管理"入口（`item_menu_row` 复用），新增账户图标 `drawable/ic_account.xml`。
- `AndroidManifest.xml`：注册 `AccountManageActivity`（`exported=false`, `parentActivityName=.MainActivity`）。
- `ChartFragment` + `ChartViewModel` + `fragment_chart.xml`：新增"账户资金"卡片（总资产 + 各账户余额），数据源 `observeAccountBalances()`，复用统一 Token。
- `strings.xml`/`dimens.xml`/`colors.xml`：账户相关文案与 Token。
- 验收：可增/改/归档账户；被引用账户禁止删除；余额计算正确且与重算一致。

## Phase 3：转账（Step 5）
- `TransactionEditActivity`：类型切换由二选改为三选（支出/收入/转账）；选"转账"时隐藏分类选择、显示"转出账户 + 转入账户"两个选择器；校验两账户必填且不同（`TransferValidator`）。
- `TransactionEditViewModel`：`selectType` 支持 `TYPE_TRANSFER`；账户列表派生。
- `Repository.saveTransaction`：转账在同一事务内同时更新转出/转入两账户余额；不写 category。
- 记录列表 `TransactionListAdapter` + `item_transaction_row.xml`：转账行展示"转出账户 → 转入账户"与中性金额色（不计收支）；`RecordListItem.Header` 当日合计不含转账。
- 测试：`TransferCalculatorTest`（转账不污染收支统计、两账户余额此消彼长、总资产不变、两账户相同被拒）。

## Phase 4：搜索 + 筛选（Step 6）
- 新增 `data/model/SearchFilter.java`（关键词、日期区间、类型集合、分类 id、账户 id、金额区间）与 `data/model/SearchResult.java`（列表 + 笔数/支出/收入合计）。
- `TransactionDao.search(...)`：可选参数模式 `(:kw IS NULL OR note LIKE '%'||:kw||:' OR c.name LIKE ... OR a.name LIKE ...)` + 类型/分类/账户/金额/日期条件；返回 `LiveData<List<TransactionItem>>`。
- `SearchTransactionsUseCase`：组装 filter → 查询 → 结果与合计（合计排除转账，复用 StatisticsCalculator）。
- UI：`RecordFragment` 顶栏新增搜索按钮进入搜索态；新增 `ui/search/SearchActivity.java`（或记录页内搜索态）+ `SearchViewModel` + `res/layout/activity_search.xml`；筛选 Chips（日期/类型/分类/账户/金额区间）+ `item_filter_chip.xml`；结果复用 `TransactionListAdapter`，顶部显示"共 N 笔 / 支出 / 收入"。
- 新增 `drawable/ic_search.xml`；`strings.xml` 搜索/筛选文案。
- 测试：`TransactionSearchFilterTest`（关键词命中备注/分类/账户名、组合筛选、金额边界、结果统计与列表一致）。

## Phase 5：CSV 导出 + 导入（Step 7）
- `importexport/CsvFormatter.java`：导出列 `交易ID,类型,金额(元),分类,账户,转入账户,日期(yyyy-MM-dd),时间(HH:mm),备注,创建时间,更新时间`；金额以元两位小数输出；RFC4180 转义（逗号/引号/换行/前导 BOM）。
- `importexport/CsvParser.java` + `ImportTransactionsUseCase.java`：解析 → 字段识别（按表头名）→ 逐行校验（金额可解析且>0、类型合法、日期可解析、分类/账户存在否则按规则跳过、转账双账户且不同）→ 生成预览（`ImportPreview`：有效行 + 错误行含原因）→ 用户确认 → 批量插入（事务）→ 结果报告（成功/跳过/错误计数）；重复数据检测（同日期+时间+金额+分类+账户+备注视为疑似重复，计入"跳过"并提示），杜绝静默重复写入。
- `importexport/ExportTransactionsUseCase.java`：全量/按筛选导出，IO 线程写 SAF Uri，展示进度。
- UI：`ui/importexport/DataExportActivity.java` + `DataImportActivity.java`（含预览列表 `ImportPreviewAdapter` + 结果对话框）；`res/layout/activity_data_export.xml`、`activity_data_import.xml`、`item_import_preview.xml`；`MineFragment` 新增"数据导入/数据导出"入口；`AndroidManifest` 注册。
- 测试：`CsvExportFormatterTest`（金额格式、转义、列顺序）、`CsvImportParserTest`（正常/缺字段/金额非法/日期非法/重复/转账缺账户）。

## Phase 6：分类预算 + 预算提醒（Step 8，P1）
- `BudgetDao`：`observe(year,month,categoryId)`、`observeCategoryBudgets(year,month)`、`upsert`（依赖新唯一索引）、`delete(year,month,categoryId)`。
- `Repository.saveBudget` 扩展 `categoryId` 参数（0=总预算，兼容 V1 调用）。
- `CalculateBudgetUseCase`：总预算 = 总支出/总预算；分类预算 = 该分类本月支出/该分类预算；状态沿用 `BudgetState` 阈值（<80% 正常、80%-100% 接近、>100% 超支），分类预算不反向限制记账，仅提醒/分析。
- UI：`BudgetSettingActivity` + `activity_budget_setting.xml` 扩展为"总预算 + 分类预算列表"（每分类一行：预算额/已用/进度/状态色，复用现有 `renderStatus` 语义色）；`ChartFragment` 新增"预算完成度"卡片（总 + 各分类百分比）。
- 测试：`BudgetCategoryTest`（分类使用率、边界 80%/100%、总预算兼容、哨兵 0 唯一性）。

## Phase 7：本地备份 / 恢复（P1）
- `importexport/BackupSerializer.java`：版本化 JSON（`{"schemaVersion":3,"accounts":[],"categories":[],"transactions":[],"budgets":[],"recurring":[],"settings":{}}`），`org.json` 实现；文件名 `minimalist-bookkeeping-backup-v3-<时间戳>.json`。
- `importexport/RestoreUseCase.java`：覆盖恢复 —— 二次确认弹窗明示"将覆盖当前本地数据"（并建议/可选先自动备份当前数据）→ 单事务内清空各表 → 按 JSON 重插 → 重算全部账户余额 → 统计经 LiveData 自动刷新。仅覆盖恢复，不做智能合并/冲突解决/增量。
- UI：`ui/importexport/BackupActivity.java` + `RestoreActivity.java`（SAF CreateDocument/OpenDocument，进度与结果反馈）；`MineFragment` 新增"本地备份/本地恢复"入口；`AndroidManifest` 注册。
- 验收：备份含全部 V2 本地数据；恢复前提示覆盖；恢复后账单/账户/预算/分类一致且统计刷新。

## Phase 8：周期账单（Step 9，P1）
- `RecurringTransactionEntity`（Phase 1 已建表）：`id,name,type,amount,category_id,account_id,frequency(DAILY/WEEKLY/MONTHLY/YEARLY),interval(默认1),start_date,end_date(0=无),next_run_date,is_enabled,note,created_at,updated_at`。
- `GenerateRecurringTransactionsUseCase`：计算 `next_run_date <= today 且 is_enabled` 的到期 occurrence（含 App 关闭期间累积的多期），生成"待确认"列表；不后台静默创建。用户确认后：插入交易（date=occurrence 日）+ 幂等推进 `next_run_date` 到下一期（月/年按 day-of-month 夹到当月最后一天，周按 weekday，日按 +interval）；停用即不再生成；到期规则不重复生成。
- UI：`ui/recurring/RecurringManageActivity.java` + `RecurringViewModel` + `RecurringAdapter`（列表/新增/编辑/停用）+ `activity_recurring_manage.xml`、`dialog_recurring_edit.xml`、`item_recurring_row.xml`；App 启动（`MainActivity` onResume 或首帧）检测待确认 → 提示"有 N 笔周期账单待记账" → 一键确认对话框。
- `MineFragment` 新增"周期账单"入口；`AndroidManifest` 注册；频率选择器（每天/每周/每月/每年）。
- 测试：`RecurringTransactionCalculatorTest`（四种频率、跨月夹取、多期补生成、停用、幂等不重复）。

## Phase 9：账户流水详情 + 数据管理收尾（P1/P2）
- `ui/account/AccountDetailActivity.java` + `AccountDetailViewModel` + `activity_account_detail.xml`：单账户当前余额 + 该账户流水（含转入/转出），复用 `TransactionListAdapter`；账户管理页点击进入。
- `DataManageActivity` + `activity_data_manage.xml`：存量统计增加"账户数""周期账单数"；`clearAllData` 已覆盖新表。
- `AccountBalanceValidator` 接入：启动或账户页触发一次"缓存 vs 重算"校验，不一致自动以重算纠正（开发期一致性保障）。
- P2 打磨：账户排序（复用 `moveCategory` 范式）、筛选 Chips 体验、批量删除/编辑（可选）、字体放大适配核对。

## Phase 10：Migration + 一致性 + 性能验证（Step 10）
- `MigrationTest`（`androidTest`，用 `MigrationTestHelper` + 已导出的 v2 schema）：V1 空库→V2、含账单、含分类、含预算、大量账单五种场景；断言迁移后表结构、外键、索引、默认账户播种、budget 哨兵、历史账单 account_id 为 NULL 且数据零丢失。
- 一致性测试：账户余额缓存与 `CalculateAccountBalanceUseCase` 重算在增/改/删/转账后恒等。
- 性能：构造 10k/50k/100k 交易，验证记录页滚动、搜索不卡顿、图表加载、周期选择器不再全量扫描、导入 10k 显示进度并完成。
- 版本：`build.gradle.kts` `versionCode 2→3`、`versionName 1.1.1→2.0.0`。
- 构建验证（PowerShell，用 `;` 不用 `&&`）：
```
$env:JAVA_HOME="D:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug ; .\gradlew.bat :app:testDebugUnitTest ; .\gradlew.bat :app:connectedDebugAndroidTest
```

## 文件变更汇总（按 Phase）
- 新增实体/DAO：`AccountEntity`、`AccountDao`、`RecurringTransactionEntity`、`RecurringTransactionDao`。
- 新增 model：`SearchFilter`、`SearchResult`、`ImportPreview`/`ImportRowResult`、`AccountBalance`（联表投影）、`CategoryBudgetState`、`RecurringDue`。
- 新增 domain：account/transaction/budget/recurring/importexport 下约 12 个 UseCase/工具类（见"架构分层"）。
- 新增 UI：`AccountManageActivity`、`AccountDetailActivity`、`SearchActivity`、`DataExportActivity`、`DataImportActivity`、`BackupActivity`、`RestoreActivity`、`RecurringManageActivity` 及对应 ViewModel/Adapter。
- 新增 layout：账户/搜索/导入导出/备份恢复/周期账单相关 activity、dialog、item（约 15+ 个）；新增 drawable：`ic_account`、`ic_search`、`ic_transfer`、`ic_backup`、`ic_import`、`ic_export`、`ic_recurring`。
- 修改：`AppDatabase`（v3+MIGRATION_2_3+exportSchema+播种）、`TransactionEntity`、`TransactionItem`、`BudgetEntity`、`TransactionDao`、`BudgetDao`、`BookkeepingRepository`、`StatisticsCalculator`、`DateLabels`、`CalendarSummaryDialog`、`RecordFragment`、`ChartViewModel`、`ChartFragment`、`TransactionEditActivity(+ViewModel)`、`TransactionListAdapter`、`BudgetSettingActivity`、`DataManageActivity`、`MineFragment`、`MainActivity`、`BookkeepingApp`、`DefaultData`、`AndroidManifest.xml`、`fragment_record.xml`、`fragment_chart.xml`、`fragment_mine.xml`、`activity_transaction_edit.xml`、`activity_budget_setting.xml`、`activity_data_manage.xml`、`item_transaction_row.xml`、`item_calendar_day.xml`、`strings.xml`、`dimens.xml`、`colors.xml`、`build.gradle.kts`、`libs.versions.toml`。
- 新增测试：`AccountBalanceCalculatorTest`、`TransferCalculatorTest`、`TransactionSearchFilterTest`、`CsvExportFormatterTest`、`CsvImportParserTest`、`BudgetCategoryTest`、`RecurringTransactionCalculatorTest`、`MigrationTest`；补充 `StatisticsCalculatorTest`、`DateUtilTest`。

## 验收对齐（文档第 31 章）
账户（增/改/归档、引用禁删、余额正确且与重算一致）、交易（收支必选账户、转账双账户且不同、转账不入收支统计）、搜索（关键词命中备注/分类/账户、组合筛选、结果统计与列表一致）、导入导出（CSV 导出格式正确、导入可预览、错误行不静默写入、成功/跳过/失败计数、无提示不重复）、备份恢复（含全部本地数据、恢复前提示覆盖、恢复后一致且统计刷新）、预算（总预算兼容 V1、分类预算使用率正确、超支状态正确）、周期账单（四频率可建、可停用、不重复生成、生成前可确认）。

## 假设项（Assumptions）
1. 历史账单迁移后 `account_id` 保持 NULL，不归属任何账户、不参与账户余额（如需可后续提供"批量归属到某账户"工具）。
2. 业务日期继续用 epoch millis（Risk E 延后），新增表沿用同一口径。
3. 总预算用 `category_id=0` 哨兵而非 NULL（规避 SQLite 唯一索引 NULL 语义），对上层语义等价于文档的"category_id=null=总预算"。
4. 转账交易 `category_id=NULL`；分类管理 UI 不显示转账、分类选择器不含转账。
5. 备份/CSV 文件经 SAF 由用户选择位置，App 不申请存储权限、不联网。
6. 周期账单采用"到期生成待确认 + 用户一键确认"，不做后台自动写入。
7. 本轮不实现 P2 中的云同步/登录/AI/OCR/投资等（文档明确归入 V3）。