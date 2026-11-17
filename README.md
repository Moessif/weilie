# 围猎 · 普通手机基站覆盖实测与协同搜捕系统

> 面向公安一线「找不到、围不住」的实战痛点：**不依赖专业路测设备、不需要精确地图选点**，
> 用民警随身手机把「上级下发的基站编号」测成一张**可信的实测覆盖范围**，并支持多人分区协同围捕、现场取证与在线验真。

本仓库是可协作开发的主仓库，包含四个部分：

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| `android/` | 围猎客户端（现场实测 / 多人协同 / 取证相机 / 静默跟踪） | Kotlin · Jetpack Compose · 百度地图 SDK · CameraX |
| `server/` | 协同与取证服务端（含管理站后端、照片验真、基站库代理） | Kotlin · Ktor 2.3 · Netty · SQLite |
| `admin-web/` | 管理站前端（组织/用户/激活码/房间/照片/审计/运维） | Vue 3 · TypeScript · Vite · Element Plus · ECharts |
| `docs/` | 立项与研究材料、协议与运维文档、各版本变更说明、申报书 | Markdown / docx |

**当前版本**：客户端 `v0.22.0`（versionCode 58）· 服务端 `v1.12.0` · 管理站 `v1.12.0`

安装包与服务端 jar 不打进仓库，统一发布在 [Releases](../../releases)：

- `weilie-v0.22.0-debug.apk` — Android 8.0+（minSdk 26，targetSdk 35）
- `weilie-server-v1.12.0-all.jar` — 需要 JDK 17

---

## 一、它解决什么问题

实战中，上级通常只能给一个基站编号（如 `NCI 5677244420` / `ECI 123456789`），而"这个基站到底覆盖哪一片"既没有现成数据，也不适合要求民警在 GIS 上精确点选。围猎的做法：

1. **目标解析**：把基站编号（ECI / NCI / LAC-CI / 经纬度+半径）解析成可比较的统一编号，并展示 gNB/eNB、小区号、PCI、频段。
2. **走测采样**：手机按 1s 间隔采集服务小区 + 邻区 + GPS，逐点判定「主区命中 / 邻区命中 / 脱离」，分别用实心点、彩色邻区点、空心点绘制。
3. **实测成面**：对有效实心点做 GPS 双重安检（精度/最小间距）、卡尔曼平滑、Douglas-Peucker 抽稀、Alpha Shape 凹包成面；点不足时退化为路线条带。
4. **多人围捕**：队长建房间（队伍码/二维码），下发任务区、自由绘制范围、指定队员分区；队员实时上报位置与采样，队长地图汇总，支持导航到标点。
5. **现场取证**：拍照即烧录水印（时间/坐标/基站/备注），照片上送服务端登记并做 HMAC 验签，任何设备都可通过验证码核验真伪。
6. **静默跟踪**：工具箱里一键开启，只靠震动区分「主区命中 / 邻区命中 / 脱离」，不亮屏不发声，适合隐蔽接近。

## 二、仓库结构

```
android/                    Android 客户端
  app/src/main/java/com/gakj/weilie/
    core/                   数据层：telephony 采集、fitting 拟合算法、sampling、sync、verify、photo、export
    ui/                     地图、工具箱、相机、多人协同、历史、设置、主题
  app/src/test/             单元测试（算法与协议）
server/                     服务端（Ktor）
  src/main/kotlin/com/gakj/weilie/server/
    RoomWs.kt RoomStore.kt  房间协同 WebSocket 与状态
    PhotoRegistry.kt        照片登记与 HMAC 签名
    PhotoWebVerify.kt       /v/{code} 验真页与文件比对
    admin/                  管理站后端（账号、组织、激活码、审计、运维）
    resources/admin/        管理站前端构建产物（由 admin-web 构建后拷入）
  src/test/                 服务端测试（接口、安全加固、导出、分区）
admin-web/                  管理站前端源码
docs/                       文档
  INDEX.md                  文档索引（先看这个）
  admin/                    管理站设计说明、安全审计报告、运维手册、线上截图
  assets/                   文档图片
tools/                      构建/测试/部署/分析脚本
```

## 三、开发环境

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | 客户端与服务端都用它编译 |
| Android SDK | compileSdk 35 / build-tools 35 | 客户端 |
| Gradle | 8.9（客户端用自带 wrapper 8.7） | 无需全局安装，用 `gradlew` |
| Node.js | 20+（推荐 22） | 管理站前端 |
| pnpm | 9+ | 管理站前端（仓库带 `pnpm-lock.yaml`） |

客户端首次构建：把 `android/local.properties.example` 复制为 `android/local.properties`，填入自己的 SDK 路径：

```properties
sdk.dir=C\:\\AndroidDev\\android-sdk
```

## 四、构建

### 1. 客户端 APK

```bash
cd android
./gradlew :app:assembleDebug          # Windows: gradlew.bat :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

> 客户端已内置百度地图 SDK 的 `.so` 与 AAR（`app/libs`、`app/src/main/jniLibs`），
> 换自己的百度 AK 时改 `AndroidManifest.xml` 里的 `com.baidu.lbsapi.API_KEY`，并在百度控制台绑定包名 `com.gakj.weilie` + 签名 SHA1。

### 2. 服务端 jar

```bash
cd server
./gradlew test fatJar
# 产物：build/libs/weilie-server-all.jar
```

本地运行：

```bash
WEILIE_DATA_DIR=./data java -Xms64m -Xmx320m -jar build/libs/weilie-server-all.jar
# 健康检查：curl http://127.0.0.1:8080/api/health
```

服务端也支持 Docker 与 systemd，见 `server/README.md`、`server/Dockerfile`、`server/weilie.service`。

### 3. 管理站前端

```bash
cd admin-web
pnpm install
pnpm run build                         # 输出 admin-web/dist
```

管理站页面由服务端托管在 `/admin/`，构建后需要把 `admin-web/dist` 的内容复制到
`server/src/main/resources/admin/`，再重新打服务端 jar：

```bash
# Windows PowerShell
Remove-Item ..\server\src\main\resources\admin\* -Recurse -Force
Copy-Item .\dist\* ..\server\src\main\resources\admin\ -Recurse -Force

# macOS / Linux
rm -rf ../server/src/main/resources/admin/* && cp -r dist/* ../server/src/main/resources/admin/
```

服务端启动后访问 `http://<host>:8080/admin/`。首次启动会自动创建超管账号
`admin`，随机初始口令写在数据目录的 `superadmin-initial-password.txt`，首次登录强制改密。

## 五、部署

生产环境（单机 Docker 或 systemd 均可）：

1. 准备 JDK 17 运行环境与可写数据目录；
2. 打 `weilie-server-all.jar`，放到服务器并配置 `server/weilie.service`（或 `docker compose up -d`）；
3. 放行 TCP 8080；浏览器访问 `/admin/`，用 `admin` + 初始口令登录改密；
4. 在管理站里建组织 → 建用户 → 发激活码；手机端用激活码激活登录；
5. 手机端「设置 → 服务器地址」填 `http://<host>:8080`。

关键环境变量（完整表见 `docs/admin/运维手册.md` 与 `server/README.md`）：

| 变量 | 作用 |
| --- | --- |
| `WEILIE_DATA_DIR` | 数据目录（房间快照、照片、审计、数据库） |
| `WEILIE_ADMIN_TOKEN` | 管理令牌，保护 `/metrics`、`/api/rooms` |
| `WEILIE_HMAC_SECRET` | 照片验签密钥（不设则用 `data/hmac.secret`，**务必备份**） |
| `WEILIE_SUPERADMIN_USER` / `WEILIE_SUPERADMIN_PASSWORD` | 首启引导超管 |
| `WEILIE_TRUST_PROXY` | 放在反向代理后面时设为 `1`，才信任 `X-Forwarded-For` |
| `WEILIE_COOKIE_SECURE` | HTTPS 环境设为 `1`，会话 Cookie 带 `Secure` |
| `OPENCELLID_KEY` | 基站库代理密钥（只放服务端，**不要打进 App**） |

## 六、协议与兼容性

- 房间协同走 WebSocket，消息定义在**两端同名文件** `Protocol.kt`（`android/.../core/sync/Protocol.kt` 与 `server/.../Protocol.kt`）。
  **改动协议必须两端同步提交**，字段一律带默认值，保证旧客户端仍能连新服务端。
- 客户端与服务端版本可独立升级：服务端 v1.12 兼容旧 6 位邀请码，新邀请码为 8 位字母数字。
- 取证照片验真：`POST /api/photo/register` 登记 → `GET /api/photo/verify?code=` 查询 → `GET /v/{code}` 网页验真。

## 七、安全与合规

- 仓库内**不含**任何服务器口令、HMAC 密钥、云厂商 AccessKey、OpenCelliD Key；这些都在 `.gitignore` 中排除，请勿提交。
- 数据目录里的 `hmac.secret`、`admin.db`、照片原图属于敏感数据，禁止外传。
- 服务端 v1.12.0 已内置：角色门（普通手机账号不能进管理站）、强制改密、双桶登录限流、邀请码校验、上传完整性校验、统一安全响应头与 CSP、请求体上限。
- 已知待办见 `docs/admin/安全审计报告-2026-11-17.md` 第 5 章（房间强制凭证、房间组织隔离、TLS 反代、依赖升级）。
- 本项目面向教学与课题研究，**现场使用须遵守数据安全与个人信息保护相关规定**。

## 八、团队协作约定

- 分支：`main` 保持可构建；功能开发开 `feat/<主题>`，修复开 `fix/<主题>`，合并前自测。
- 提交信息：`<模块>: <做了什么>`，例如 `android: 修复静默跟踪在 NSA 下的状态抖动`。
- 涉及协议、数据库表结构、接口路径的改动，请在 PR 描述里写清兼容性影响。
- 版本号：`android/app/build.gradle.kts` 的 `versionCode` / `versionName`；服务端 `Application.kt` 的 `SERVER_VERSION`；管理站同步改 `admin-web` 里展示的版本号。
- 发布：打好 APK/jar 后在 Releases 建 tag（如 `v0.22.0`）附上产物，仓库里只保留源码。
- 新增文档放 `docs/`，并在 `docs/INDEX.md` 增一行索引。

## 九、文档入口

| 文档 | 内容 |
| --- | --- |
| `docs/INDEX.md` | 文档索引（含早期看板状态，仅供查阅） |
| `docs/研究报告-v1.2.md` | 立项背景、技术路线、算法、试验与结论 |
| `docs/协议-v1.3.md` | WebSocket / HTTP 协议说明 |
| `docs/v1.4-入房令牌.md` | 入房令牌设计 |
| `docs/运维手册-v1.3.md`、`docs/admin/运维手册.md` | 部署运维 |
| `docs/admin/视觉重做说明.md` | 管理站设计系统与页面说明 |
| `docs/admin/安全审计报告-2026-11-17.md` | 全项目白盒审计结论与修复状态 |
| `docs/申报材料清单与填写指南.md` | 申报材料清单 |
| 各 `docs/v0.x-*.md` | 逐版本变更说明 |

## 十、许可与致谢

本项目为参加中国人民公安大学学生科技应用创新大赛的研究成果，代码仅供学习与课题研究使用；
第三方组件版权归各自作者所有（Jetpack Compose、Ktor、Vue、Element Plus、ECharts、百度地图 SDK、ZXing 等）。
