# Android-Minimalist-Bookkeeping
Minimalist Bookkeeping


## 版本演进

- **V1 / V1.1**：本地记账核心闭环（记 → 看 → 懂 → 控），Room + ViewModel/LiveData + Material 3，完全离线可用。
- **V2 / V2.1**：账户与资金管理（转账、余额模型）、分类预算、周期账单、CSV 导入导出、本地备份恢复；V2.1 收尾体验与一致性（周期账单锚点、图表轴、搜索选择器等）。
- **V3（当前）**：在保持本地优先的前提下，新增**可选云端同步**——邮箱注册 + 邮箱验证 + JWT/Refresh Token 多设备登录，家庭自建服务器（Spring Boot + PostgreSQL + Docker Compose），syncId/version/baseVersion 乐观并发 + LWW + Soft Delete + 冲突日志的双向增量同步。

## V3 快速开始（云同步）

1. 启动服务器（见 [server/README.md](server/README.md)）：
   ```bash
   cd server && docker compose up -d --build
   ```
2. App 内：我的 → 同步中心 → 填服务器地址（如 `http://<家庭电脑IP>:8080`）。
3. 登录 → 邮箱验证 → 打开「云端同步」开关 → 确认首次同步统计。
4. 服务器关机 / 断网时本地记账完全不受影响；恢复后自动续传（指数退避，最长 30 分钟）。

产品 / 架构基线见 `.qoder/plans/极简记账App_V3_产品设计与开发基线.md`，
开发计划与实施记录见 `.qoder/plans/极简记账_V3_开发计划.md`。

## 构建与测试

```bash
./gradlew assembleDebug            # 客户端 APK
./gradlew testDebugUnitTest        # JVM 单测（216 个）
./gradlew connectedDebugAndroidTest  # 仪器测试（需真机/模拟器，19 个）
cd server && ./gradlew test        # 服务端测试（H2，11 个）
```
