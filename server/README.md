# 极简记账 · 自建同步服务器（V3）

Spring Boot 3.5 + PostgreSQL + Flyway + JWT。本地优先 App 的可选云端（基线第 6 章）。

## 本地开发

```bash
# 1. 起 PostgreSQL（或用下面 compose 只起数据库）
docker run -d --name bookkeeping-pg -p 5432:5432 \
  -e POSTGRES_DB=bookkeeping -e POSTGRES_USER=bookkeeping -e POSTGRES_PASSWORD=bookkeeping \
  postgres:16-alpine

# 2. 跑服务器（默认连 localhost:5432，验证邮件只打日志）
./gradlew bootRun

# 3. 测试（H2 内存库，兼容 PostgreSQL 方言）
./gradlew test
```

## 家庭电脑部署

局域网直连（手机与电脑同网段）：

```bash
JWT_SECRET=$(openssl rand -base64 48) docker compose up -d --build
```

公网 HTTPS（推荐，Caddy 自动证书）：

```bash
cp .env.example .env   # 填 DOMAIN / JWT_SECRET / DB_PASSWORD
docker compose -f docker-compose.https.yml up -d --build
```

`.env` 示例：

```text
DOMAIN=sync.example.com
JWT_SECRET=<openssl rand -base64 48 的输出>
DB_PASSWORD=<强密码>
MAIL_ENABLED=true
MAIL_DEV_LOG_ONLY=false
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USER=...
SMTP_PASSWORD=...
```

安全要点（基线第 30 章）：

- PostgreSQL 只在 compose 内网可达，不映射宿主端口
- 密码 BCrypt；Refresh Token SHA-256 落库、旋转、设备绑定、可吊销
- 认证接口 IP 限流（60 秒窗口 30 次）
- 全部业务数据按 JWT userId 隔离，不信任客户端传入身份
- 日志不记录密码与完整令牌；验证 Token 单次使用、24 小时过期

## 手机 App 配置

「我的 → 同步中心 → 服务器地址」填：

- 局网：`http://<家庭电脑局域网 IP>:8080`
- 公网：`https://<你的域名>`

## API 一览（API Version 1 / Sync Protocol Version 1）

```text
POST /api/v1/auth/register                    注册（发验证邮件）
GET  /api/v1/auth/verify-email?token=         邮箱验证落地页
POST /api/v1/auth/resend-verification         重发验证邮件
POST /api/v1/auth/login                       登录（返回 access/refresh + 设备）
POST /api/v1/auth/refresh                     刷新（旋转 refresh token）
POST /api/v1/auth/logout                      退出当前设备
POST /api/v1/auth/logout-all                  退出全部设备
GET  /api/v1/devices                          设备列表
POST /api/v1/devices/{id}/revoke              吊销设备
DELETE /api/v1/account                        注销（二次确认密码）
GET  /api/v1/sync/status                      可用性 + 邮箱验证状态（V3.1 起附服务器版本与恢复代际）
GET  /api/v1/sync/bootstrap/summary           首次同步云端统计
POST /api/v1/sync/changes/push                增量推送（baseVersion 乐观控制 + LWW，响应附 recoveryEpoch）
POST /api/v1/sync/changes/pull                增量拉取（change_id 游标，响应附 recoveryEpoch）
GET  /api/v1/sync/conflicts?limit=50          冲突历史摘要（V3.1）

## V3.1 服务器管理（备份 / 恢复，仅管理员）

GET  /api/v1/server/health                    健康检查（公开：状态/版本/数据库/磁盘/代际/最近备份）
GET  /api/v1/server/stats                     管理统计（管理员）
GET  /api/v1/server/backup                    备份列表（管理员）
POST /api/v1/server/backup                   立即备份（管理员）
GET  /api/v1/server/backup/{name}/meta        备份详情（管理员）
POST /api/v1/server/backup/{name}/restore    恢复备份（管理员；epoch+1，设备重新登录并重新收敛）

浏览器管理页：`http://server-address/admin`（登录后可查看状态、执行备份与恢复）。
管理员判定：配置 `APP_ADMIN_EMAILS`（逗号分隔）；未配置时默认最早注册的账号。
备份目录：`BACKUP_DIR`（默认 `./backups`），每日自动备份默认 03:00（`BACKUP_DAILY_CRON`），
保留策略为最近 7 天每日一份 + 4 周每周一份 + 12 个月每月一份；备份不含任何登录令牌。
```

所有 `/api/v1/**` 请求需带请求头 `X-Api-Version: 1` 与 `X-Sync-Protocol-Version: 1`；
受保护接口另带 `Authorization: Bearer <accessToken>`。豁免版本头的例外：`/api/v1/auth/verify-email`
（浏览器打开）与 `/api/v1/server/health`（浏览器 / curl 排障）。
