# 围猎管理平台 API 契约（v0.1 / M1）

> 服务端：`weilie-platform/server`（Ktor 2.3.12，fatJar 内嵌管理站）。
> 管理站前端：`weilie-platform/web`（Vue3+TS+Vite+Element Plus+ECharts）。
> 部署形态：管理站静态资源打进 jar（`/admin`），API 前缀 `/api/admin`；手机端接口前缀 `/api/client`。
> 约定：所有响应 JSON；失败返回非 2xx + `{"ok":false,"error":"错误说明"}`；成功含 `"ok":true`。

## 1. 认证与会话

- 登录：`POST /api/admin/auth/login`
  - 请求：`{"username":"...","password":"..."}`
  - 响应：`{"ok":true,"user":{...},"csrfToken":"..."}`，并 `Set-Cookie: WEILIE_ADMIN=<token>; HttpOnly; SameSite=Lax; Path=/`
- 当前用户：`GET /api/admin/auth/me` → `{"ok":true,"user":{...},"csrfToken":"..."}`（未登录 401）
- 登出：`POST /api/admin/auth/logout` → `{"ok":true}`
- 修改密码：`POST /api/admin/auth/change-password` `{"oldPassword":"...","newPassword":"..."}`
- **写操作必须带** `X-CSRF-Token: <csrfToken>`（POST/PATCH/DELETE）；缺/错 → 403。
- 会话 8 小时滑动过期，30 分钟空闲过期；登录失败 5 次锁 15 分钟。

`user` 对象：
```json
{"id":1,"username":"admin","displayName":"系统管理员","role":"SUPER_ADMIN","orgId":null,"orgName":null,"status":"ACTIVE","mustChangePassword":false}
```
角色：`SUPER_ADMIN`（全量）/ `ORG_ADMIN`（本组织）/ `USER`（手机端用户，管理站只读自身）/ `AUDITOR`（只读）。
用户状态：`PENDING`（待激活）`ACTIVE` `SUSPENDED` `LOCKED` `DELETED`；组织状态：`PENDING` `ACTIVE` `SUSPENDED`。

## 2. 组织（Org）

- `GET /api/admin/orgs?q=&status=&page=1&size=20` → `{"ok":true,"total":N,"items":[org...]}`
- `POST /api/admin/orgs` → 建组织（可同时建组织管理员）
  ```json
  {"name":"中国人民公安大学","code":"ppsuc","contactName":"张三","contactPhone":"138...",
   "quotaUsers":50,"quotaRooms":5,"adminUsername":"ppsuc_admin","adminDisplayName":"组织管理员","adminPassword":"初始密码8位以上"}
  ```
  响应：`{"ok":true,"org":{...},"adminUser":{...}}`
- `GET /api/admin/orgs/{id}` → `{"ok":true,"org":{...},"stats":{"users":N,"activeUsers":N,"rooms":N,"samples":N}}`
- `PATCH /api/admin/orgs/{id}` → 可改 `name/contactName/contactPhone/quotaUsers/quotaRooms/status`
- `DELETE /api/admin/orgs/{id}` → 软删除（`ok:true`）

`org` 对象：`{"id":2,"name":"...","code":"ppsuc","contactName":"","contactPhone":"","status":"ACTIVE","quotaUsers":50,"quotaRooms":5,"createdAt":1763...}`

### 组织申请（公开 + 审核）
- `POST /api/public/org-applications` `{"orgName":"...","contactName":"...","contactPhone":"...","email":"","reason":""}` → `{"ok":true}`
- `GET /api/admin/org-applications?status=PENDING&page=1&size=20`
- `POST /api/admin/org-applications/{id}/approve` `{"code":"ppsuc","adminUsername":"...","adminPassword":"..."}` → 建组织+管理员
- `POST /api/admin/org-applications/{id}/reject` `{"reason":"..."}`

## 3. 用户与激活码

- `GET /api/admin/users?orgId=&q=&status=&page=1&size=20` → 列表（含 `orgName`、`deviceId`、`lastLoginAt`、`activatedAt`）
- `POST /api/admin/users` `{"orgId":2,"username":"zhangsan","displayName":"张三","phone":"","role":"USER"}` → `{"ok":true,"user":{...}}`
- `PATCH /api/admin/users/{id}` `{"displayName","phone","status","role"}`
- `DELETE /api/admin/users/{id}` 软删除
- `POST /api/admin/users/{id}/activation-code` `{"purpose":"ACTIVATE"}` → `{"ok":true,"code":"AB12CD34","expiresAt":1764...}`（重复调用作废旧码）
- `POST /api/admin/users/{id}/reset-password` → 生成 `RESET_PASSWORD` 激活码（同上结构）
- `POST /api/admin/users/{id}/unbind-device` → 解绑设备（激活码可重新使用/重置密码）
- `POST /api/admin/users/import` `{"orgId":2,"rows":[{"username":"u1","displayName":"甲","phone":""}]}` → `{"ok":true,"created":N,"failed":[{"row":2,"reason":"用户名重复"}],"codes":[{"username":"u1","code":"..."}]}`
- `GET /api/admin/activation-codes?orgId=&status=UNUSED&page=1&size=20`
- `POST /api/admin/activation-codes/{id}/revoke` → 作废
- `GET /api/admin/users/{id}/codes` → 该用户历史激活码

激活码：8 位大写字母+数字（去掉易混 0/O/1/I），默认 7 天有效，一次性（用后 `usedAt`）。

## 4. 仪表盘（M1 提供 summary，趋势/图表 M3 补齐）

- `GET /api/admin/dashboard/summary` →
  ```json
  {"ok":true,
   "kpi":{"orgs":12,"orgsDelta":2,"users":386,"activeUsers":310,"pendingUsers":76,
          "onlineMembers":27,"onlinePeak":41,"rooms":56,"activeRooms":9,
          "todaySamples":12480,"samplesDeltaPct":18.0,"photos":37,"audits24h":128},
   "server":{"version":"1.11.0","uptimeSec":3600,"roomsInMemory":56,"onlineMembers":27}}
  ```
- `GET /api/admin/dashboard/timeseries?metric=samples|online|ws&range=24h|7d|30d` → `{"ok":true,"points":[{"ts":1794...,"value":123}]}`（M3）
- `GET /api/admin/dashboard/top-orgs?hours=24` → `{"ok":true,"items":[{"orgId":2,"orgName":"...","samples":1234}]}`（M3）
- `GET /api/admin/dashboard/heatmap?days=7` → `{"ok":true,"cells":[{"weekday":0,"hour":13,"value":42}]}`（M3）
- `GET /api/admin/dashboard/user-status` → `{"ok":true,"items":[{"status":"ACTIVE","count":310}]}`（M1）
- `GET /api/admin/dashboard/room-status` → `{"ok":true,"items":[{"key":"WITH_ZONE","count":22}]}`（M3）

## 5. 房间 / 照片 / 审计 / 运维（M2）

- `GET /api/admin/rooms?orgId=&status=&page=1&size=20`、`GET /api/admin/rooms/{roomId}`（成员/区域/采样数/组织）、`GET /api/admin/rooms/{roomId}/samples?limit=1000`、`POST /api/admin/rooms/{roomId}/close`
- `GET /api/admin/photos?page=&size=&orgId=&roomId=`、`GET /api/admin/photos/{code}/file`（原图流）、`DELETE /api/admin/photos/{code}`
- `GET /api/admin/audit?action=&orgId=&from=&to=&page=&size=`、`GET /api/admin/audit/stream`（SSE 实时流）
- `GET /api/admin/ops/health`、`GET /api/admin/ops/metrics`、`GET /api/admin/ops/logs?lines=200`、`POST /api/admin/ops/purge-rooms`、`POST /api/admin/ops/backup`

## 6. 手机端（Client）接口

认证头：`Authorization: Bearer <token>`（token 30 天滑动）。
- `POST /api/client/activate` `{"code":"AB12CD34","password":"...","deviceId":"...","deviceName":"REDMI K80 Pro"}`
  → `{"ok":true,"token":"...","user":{"id":5,"username":"zhangsan","displayName":"张三","orgId":2,"orgName":"..."}}`
  - 激活码用于 `ACTIVATE`：设置密码、状态 PENDING→ACTIVE、绑定 deviceId、码置已用。
  - 激活码用于 `RESET_PASSWORD`：重置密码并解绑旧设备，`ok:true` 后需重新登录。
- `POST /api/client/login` `{"username":"...","password":"...","deviceId":"..."}` → `{"ok":true,"token":"...","user":{...}}`
  - 校验：ACTIVE、密码正确、deviceId 与绑定一致（不一致 → 409 `{"ok":false,"error":"此账号已绑定其他设备，请联系管理员解绑","code":"DEVICE_MISMATCH"}`）。
- `GET /api/client/me` → `{"ok":true,"user":{...}}`
- `POST /api/client/logout`
- `POST /api/client/change-password` `{"oldPassword":"...","newPassword":"..."}`

## 7. 前端路由（管理站）

| 路径 | 页面 | 说明 |
|---|---|---|
| `/admin/login` | 登录 | 未登录重定向到此 |
| `/admin` | 仪表盘 | KPI + 图表（M1 先 KPI/用户状态，M3 补图表） |
| `/admin/orgs` | 组织管理 | 列表/新建/编辑/停用；组织申请审核 tab |
| `/admin/users` | 用户与账号 | 组织筛选、搜索、批量导入、激活码弹窗、解绑、重置密码 |
| `/admin/codes` | 激活码 | 列表、作废、复制、二维码 |
| `/admin/rooms` | 房间与任务 | 列表/详情/采样/导出（M2） |
| `/admin/photos` | 取证照片 | 列表/预览/下载（M2） |
| `/admin/audit` | 审计日志 | 筛选/实时流（M2） |
| `/admin/ops` | 系统监控 | 指标/健康/日志/清理/备份（M2） |
| `/admin/settings` | 设置 | 修改密码、公告、策略（M2） |

## 8. 数据与安全约定

- 数据库：`data/admin.db`（SQLite WAL）；首次启动自动建表，`PRAGMA user_version` 记 schema 版本。
- 密码：PBKDF2WithHmacSHA256，16 字节盐，210000 次迭代，存 `算法$迭代$盐b64$哈希b64`。
- 会话 token：32 字节随机 base64url；库里只存 `sha256(token)`；Cookie `WEILIE_ADMIN` HttpOnly。
- 组织隔离：非 SUPER_ADMIN 的所有查询强制带 `org_id = ?`；越权返回 404。
- 审计：登录、增删改、导出、清理都写 `admin_audit`（`actor_id/actor_name/action/target/detail/ip/ts`），保留 180 天。
- 超级管理员初始化：环境变量 `WEILIE_SUPERADMIN_USER` / `WEILIE_SUPERADMIN_PASSWORD`；缺省 `admin` / `admin12345` 且 `mustChangePassword=true`，首次登录强制改密（前端拦一次改密弹窗）。
