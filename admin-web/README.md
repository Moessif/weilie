# 围猎 · 管理中心（weilie-platform/web）

围猎 Android 蜂窝覆盖实测协同系统的管理站前端 SPA（M1 + M2）。构建产物由服务端打进 jar 的 `/admin` 路径，使用 `base: '/admin/'` + history 路由。

## 技术栈

- Vue 3.5 + TypeScript + Vite 5
- Element Plus（官方 dark 主题变量覆盖为围猎配色）
- ECharts 5 + vue-echarts（按需注册，自定义 `weilie` 主题）
- Pinia + vue-router 4
- 不引入 CDN、外部字体或 Tailwind

## 快速开始

环境要求：Node ≥ 18（本项目验证于 Node 24.21.0）、pnpm ≥ 9（验证于 11.28.5）。

```bash
cd weilie-platform/web

# 安装依赖
pnpm install

# 开发（http://127.0.0.1:5273/admin/）
pnpm dev

# 类型检查（vue-tsc --noEmit）
pnpm typecheck

# 生产构建：类型检查 + vite build，产物在 dist/
pnpm build

# 构建并本地预览（http://127.0.0.1:4273/admin/）
pnpm preview

# 仅构建、跳过类型检查
pnpm build:only
```

国内网络安装慢时可指定镜像：

```bash
pnpm install --registry https://registry.npmmirror.com
```

> 若 pnpm 提示 `ERR_PNPM_IGNORED_BUILDS`（esbuild / vue-demi 的 install 脚本被拦截），执行一次 `pnpm approve-builds --all` 即可。本仓库 `pnpm-workspace.yaml` 已把 `esbuild`、`vue-demi` 加入 `allowBuilds`。

### 开发代理

`vite.config.ts` 中 `/api` 默认代理到 `http://127.0.0.1:8080`（Ktor 服务端），可用环境变量覆盖：

```bash
VITE_API_TARGET=http://127.0.0.1:9000 pnpm dev
```

## mock 开关（`VITE_USE_MOCK`）

三种取值，默认 `auto`（见 `.env.development` / `.env.production`）：

| 值 | 行为 |
| --- | --- |
| `auto` | 优先请求真实 `/api/admin`；网络错误、404/405/5xx、非 JSON 响应时自动回退 mock；**401 不回退**，直接跳登录页 |
| `on` / `true` | 强制 mock，完全不发真实请求，适合离线演示与自检 |
| `off` / `false` | 只用真实接口，适合联调 |

示例：

```bash
# macOS / Linux / Git Bash
VITE_USE_MOCK=on pnpm build
VITE_USE_MOCK=off pnpm dev
```

```powershell
# Windows PowerShell
$env:VITE_USE_MOCK='on'; pnpm build
$env:VITE_USE_MOCK='off'; pnpm dev
```

也可以使用 Vite 模式文件：`vite build --mode mock` 会读取 `.env.mock`。

### 演示账号（mock 模式）

| 账号 | 密码 | 角色 | 说明 |
| --- | --- | --- | --- |
| `admin` | `admin12345` | 超级管理员 | 首次登录强制修改密码 |
| `orgadmin` | `org12345` | 组织管理员 | 网安学院（orgId=1） |

mock 会话保存在 `localStorage` 的 `weilie.admin.mock.session`，刷新页面保持登录。mock 数据为固定种子的确定性数据：

- 12 个组织（386 用户配额内的用户分布）
- 386 个用户（306 已激活 / 58 待激活 / 14 已停用 / 8 已锁定）
- 56 个房间（22 有任务区 / 9 仅有标点 / 21 空房 / 4 已关闭）
- 37 张取证照片（约 1/3 标记为可疑，少量未上传原图）
- 80 个激活码、5 条组织申请、36 条审计事件（`GET /audit` 访问时按节奏追加，模拟实时流）

## 目录结构

```
web/
├─ src/
│  ├─ api/
│  │  ├─ client.ts        # fetch 封装：credentials、X-CSRF-Token、401 跳登录、错误提示、mock 回退
│  │  ├─ error.ts         # ApiError / errorMessage
│  │  ├─ index.ts         # 页面唯一依赖的 api 对象（真实接口与 mock 同签名）
│  │  └─ mock.ts          # M1/M2 全量 mock 实现（确定性假数据 + 文件下载）
│  ├─ components/
│  │  ├─ BaseChart.vue        # vue-echarts 包装
│  │  ├─ ChartCard.vue        # 图表卡片（标题/副标题/空状态/extra）
│  │  ├─ ChangePasswordDialog.vue
│  │  ├─ EmptyState.vue
│  │  ├─ PageHeader.vue
│  │  └─ StatCard.vue         # KPI 卡
│  ├─ layouts/AdminLayout.vue # 左侧导航 + 顶栏 + 强制改密
│  ├─ pages/
│  │  ├─ LoginPage.vue
│  │  ├─ DashboardPage.vue
│  │  ├─ OrgsPage.vue
│  │  ├─ UsersPage.vue
│  │  ├─ CodesPage.vue
│  │  ├─ RoomsPage.vue        # 房间列表 / 详情抽屉（Canvas 采样散点）/ 导出 / 关闭 / 清理
│  │  ├─ PhotosPage.vue       # 取证照片网格 / 大图预览 / 验真跳转
│  │  ├─ AuditPage.vue        # 审计日志筛选 / 自动刷新 / CSV 导出
│  │  ├─ OpsPage.vue          # 服务指标 / 存储 / TTL 与清理操作
│  │  └─ SettingsPage.vue     # 运行时设置 / 安全策略 / 修改密码
│  ├─ router/{index.ts,nav.ts}
│  ├─ stores/{auth.ts,app.ts}
│  ├─ styles/theme.css        # 深色变量、Element Plus 覆盖、全局工具类
│  ├─ types/index.ts
│  ├─ utils/{chart.ts,csv.ts,format.ts}
│  ├─ App.vue
│  └─ main.ts
├─ docs/selfcheck/            # M1 mock 模式页面自检截图
├─ docs/selfcheck-m2/         # M2 mock 模式页面自检截图
├─ vite.config.ts
├─ .env.development
├─ .env.production
└─ index.html
```

## 路由与页面

| 路由 | 页面 | 状态 |
| --- | --- | --- |
| `/admin/login` | 登录（含首次登录强制改密） | M1 完成 |
| `/admin` → 重定向 `/admin/dashboard` | 仪表盘 | M1 完成 |
| `/admin/orgs` | 组织管理（列表 + 组织申请审核） | M1 完成 |
| `/admin/users` | 用户与账号（新建/导入/激活码/重置密码/解绑设备/禁用） | M1 完成 |
| `/admin/codes` | 激活码列表 | M1 完成 |
| `/admin/rooms` | 房间与任务（列表 / 详情 / 采样散点 / 导出 / 关闭 / 清理空房） | M2 完成 |
| `/admin/photos` | 取证照片（筛选 / 网格 / 大图预览） | M2 完成 |
| `/admin/audit` | 审计日志（筛选 / 自动刷新 / 导出） | M2 完成 |
| `/admin/ops` | 系统监控（JVM、连接、存储、运维操作） | M2 完成 |
| `/admin/settings` | 系统设置（运行时参数 / 安全策略 / 修改密码） | M2 完成 |

## API 层约定

`src/api/client.ts`：

- 所有请求 `credentials: 'include'`（Cookie `WEILIE_ADMIN`）
- 写请求（非 GET）自动携带 `X-CSRF-Token`；登录/`me` 响应里的 `csrfToken` 由 auth store 写入 localStorage（键 `weilie.admin.csrf`）
- 401：清理会话并跳转 `/admin/login?redirect=...`（登录页自身不会重复跳转）
- 非 2xx / `{ ok: false }` 统一 `ElMessage.error`；`silent: true` 的请求（如登录）由页面内联展示错误
- 页面只依赖 `src/api/index.ts` 导出的 `api` 对象，真实接口与 mock 实现同签名

mock 覆盖的接口（按 `server/docs/admin-api.md` 与 `server/docs/admin-api-m2.md` 契约实现）：

- 认证：`POST /auth/login`、`GET /auth/me`、`POST /auth/logout`、`POST /auth/change-password`
- 仪表盘：`GET /dashboard/summary`、`/dashboard/timeseries?metric=samples|online|ws&range=24h|7d|30d`、`/dashboard/top-orgs?hours=`、`/dashboard/heatmap?days=`、`/dashboard/user-status`、`/dashboard/room-status`、`/dashboard/recent-rooms`
- 组织：`GET/POST /orgs`、`GET/PATCH/DELETE /orgs/{id}`
- 组织申请：`GET /org-applications`、`POST /org-applications/{id}/approve`、`POST /org-applications/{id}/reject`
- 用户：`GET/POST /users`、`PATCH/DELETE /users/{id}`、`POST /users/import`、`POST /users/{id}/activation-code`、`POST /users/{id}/reset-password`、`POST /users/{id}/unbind-device`、`GET /users/{id}/codes`
- 激活码：`GET /activation-codes`、`POST /activation-codes/{id}/revoke`
- 审计：`GET /audit`（等级/动作/关键词/组织筛选）、`GET /audit/export?format=csv`
- 房间：`GET /rooms`、`GET /rooms/{id}`、`GET /rooms/{id}/samples`、`GET /rooms/{id}/export?format=csv|geojson|kml`、`POST /rooms/{id}/close`、`POST /rooms/purge`
- 取证照片：`GET /photos`、`GET /photos/{code}/file`（mock 返回内置 SVG 缩略图）
- 运维：`GET /ops/status`、`GET /settings`、`PATCH /settings`

## 自检（mock 模式）

构建验证（最终产物为默认 `auto` 构建）：

- `pnpm typecheck`：通过，无输出。
- `pnpm build`：通过（vue-tsc + vite build），2228 modules，`✓ built in 5.95s`，exit code 0。
- `dist/index.html` 976 B，入口 `/admin/assets/index-4CQUY0k_.js`，预加载与样式资源均以 `/admin/assets/` 开头，可直接放入服务端 jar 的 `/admin`。
- 产物：`index-*.js` 约 61 KB、`vendor-vue` 115 KB、`vendor-echarts` 586 KB、`vendor-element` 960 KB；登录、仪表盘、组织、用户、激活码与 M2 五个页面均为独立路由 chunk。

M2 自检使用 `VITE_USE_MOCK=on` 构建（不请求真实后端）+ Chrome headless/CDP 渲染，`✓ built in 6.31s`，exit 0。

页面与交互自检（截图见 `docs/selfcheck/` 与 `docs/selfcheck-m2/`）：

| 截图 | 验证内容 |
| --- | --- |
| `docs/selfcheck/login.png` | 登录页双栏布局、深色主题、演示账号提示；1440×1000 |
| `docs/selfcheck/login-first-change-password.png` | 使用 `admin/admin12345` 登录成功后被强制改密弹窗拦截（背景模糊、不可关闭） |
| `docs/selfcheck/dashboard.png` | 仪表盘完整内容：5 张 KPI、采样趋势（24h/7d/30d）、在线/WS 双轴、Top8、用户状态环、房间状态条、7×24 热力图、最近房间表、实时审计流；1440×1600 |
| `docs/selfcheck/dashboard-1920.png` | 1920×1080 下 4 列分布卡布局 |
| `docs/selfcheck/dashboard-1366.png` | 1366×768 下布局与 5 列 KPI 正常 |
| `docs/selfcheck/orgs.png` | 组织列表（配额进度、状态、操作）、组织申请 tab 徽标 |
| `docs/selfcheck/users.png` | 用户列表（组织/状态/关键词筛选、角色/状态 tag、设备信息、分页） |
| `docs/selfcheck/codes.png` | 激活码列表（状态汇总 chip、用途/状态标签、复制/作废） |
| `docs/selfcheck/ops.png` | M1 阶段占位页（历史截图，M2 已替换为正式系统监控页） |
| `docs/selfcheck/dashboard-after-login.png` | 修改密码成功后跳转仪表盘，控制台无 JS error |
| `docs/selfcheck-m2/rooms.png` | 房间列表：状态 tab、组织/关键词筛选、56 条房间分页、邀请码复制、导出/关闭操作 |
| `docs/selfcheck-m2/rooms-detail.png` | 房间详情抽屉：组织/成员/采样/标点统计、Canvas 采样散点图（发现目标/未发现双色）、成员表、任务区、标点表 |
| `docs/selfcheck-m2/photos.png` | 取证照片网格：普通/可疑角标、设备与大小、未上传原图占位；共 37 张 |
| `docs/selfcheck-m2/photos-preview.png` | 大图对话框：元数据（拍摄/上传时间、经纬度、设备、大小、SHA-256）、上一张/下一张、验真页链接 |
| `docs/selfcheck-m2/audit.png` | 审计日志：等级/动作/组织/关键词筛选、等级色点、自动刷新开关、导出 CSV；共 34 条 |
| `docs/selfcheck-m2/ops.png` | 系统监控：JVM/线程/堆内存、房间与在线规模、业务指标、存储占用、TTL/清理操作 |
| `docs/selfcheck-m2/settings.png` | 系统设置：TTL 可编辑保存、只读运行时参数、安全策略说明、当前账号与修改密码入口 |

交互链路实测结果：

- 登录 → 首登强制改密（校验 8–32 位字母+数字、两次一致）→ 保存新密码 → 进入仪表盘；mock 会话刷新保持登录；`Runtime.exceptionThrown` 与 console error 均为空。
- M2：房间列表打开首行「详情」抽屉，Canvas 散点、成员、标点均渲染；照片卡片点击打开大图预览；五个页面在 1440×1000 下均无横向溢出或控制台报错。
- 文件的下载（房间 CSV/GeoJSON/KML、审计 CSV）在 mock 模式由 `mockDownload` 生成 Blob 并触发浏览器下载。

> 提示：`vite preview` 会继承开发代理，若本机 `127.0.0.1:8080` 正在运行真实后端，`auto` 模式下 `/api/admin/*` 会命中真实接口（未登录返回 401）并按设计跳登录页。仅做页面演示/截图时请使用 `VITE_USE_MOCK=on` 构建。

## M1 / M2 范围与后续

已实现（M1）：

- 登录 / 首次登录强制改密 / 修改密码 / 退出登录
- 仪表盘全部 KPI 与图表，30s 刷新实时指标、15s 刷新审计流，失败自动回退 mock
- 组织管理：搜索/状态筛选/分页、新建组织（同时创建组织管理员）、编辑、停用/启用、组织申请审核（通过/驳回）
- 用户与账号：组织/状态/关键词筛选、新建用户、CSV 批量导入（预览异常行、导入结果与失败行、下载/复制结果）、生成/作废激活码、重置密码、解绑设备、禁用/启用、解除锁定
- 激活码列表：状态汇总、筛选、复制、作废
- 响应式布局（≥1200 侧栏展开，<1200 自动收起），顶栏组织筛选与头像菜单

已实现（M2，契约 `server/docs/admin-api-m2.md`）：

- 房间与任务：状态 tab / 组织 / 关键词筛选、分页列表、邀请码复制、详情抽屉（成员、任务区、标点、Canvas 采样散点）、CSV/GeoJSON/KML 导出、关闭房间、按天数清理空房
- 取证照片：普通/可疑/关键词/仅原图筛选、卡片网格、大图预览（元数据 + SHA-256 + 验真页链接）、上一张/下一张
- 审计日志：等级/动作/组织/关键词筛选、15s 自动刷新开关、等级色点、CSV 导出
- 系统监控：服务信息（版本/运行时长/JVM/CPU/线程/堆内存）、房间与在线规模、业务指标、存储占用、TTL 与 Token 设置、清理空房
- 系统设置：运行时 TTL 修改保存、只读运行时参数、安全策略说明、当前账号与修改密码
- 文件下载统一走 `client.download()`，mock 模式由 `mockDownload` 生成 Blob（CSV/GeoJSON/KML）

留待后续 / 明确未做：

- 激活码二维码展示（当前仅展示 8 位码与复制；契约允许省略）
- 组织详情抽屉、房间真实地图底图（当前用 Canvas 散点图代替，不接地图 SDK）
- 服务端时序/热力图接口联调（契约中时序标 M3，mock 已先行实现）
- 照片验真页 `/v/{code}` 由服务端提供，管理站仅跳转链接

## 部署

`pnpm build` 后将 `dist/` 内文件放入服务端 `/admin` 静态资源目录（或打进 jar 的 `resources/admin`）。服务端需将 `/admin/*` 的未知路径 fallback 到 `/admin/index.html`，以支持 history 路由刷新。
