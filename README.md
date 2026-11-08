# 围猎（WeiLie）· 普通手机基站覆盖实测与搜捕协同系统

面向「基层民警无专业路测设备、仅有一部普通手机」的场景，用手机自身的蜂窝小区信息 + GNSS 定位，
通过**移动打点**反推目标基站的真实覆盖范围，并把结果共享给队友，用于搜捕、找人、清查等任务。

> 参赛项目：中国人民公安大学第三届学生科技应用创新大赛 · 公安网络通信与移动应用关键技术赛道
> 当前版本 **v0.4.5**（Android，versionCode 11）

---

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 单人覆盖实测 | 输入目标小区编号（ECI / NCI / LAC+CI / 经纬度），边走边打点，实时生成轨迹、凸包、覆盖等值线与拟合置信度 |
| 双卡双制式 | 4G/5G（含 NSA 锚点 + NR 小区分行显示）、移动/联通/电信各自制式与频段、NCI 习惯写法 `5677244420(1386046-4)` |
| 专家视野 | 毫秒时戳、经纬度与精度、MCC/MNC/TAC、PCI/EARFCN/NRARFCN、RSRP/RSRQ/SINR、邻区、WiFi 与厂商、小区切换轨迹 |
| 普通视角 | 五格信号条 + 白话提示（「较强 / 切换了基站」），不露 dBm 术语 |
| 取证相机 | 类水印相机版式（大字时间 / 日期 / 地址 / 蜂窝水印烧入 JPG），可疑设备类型可选，照片与地图标点双向关联 |
| 影像验真 | 照片 SHA-256 + 服务端 HMAC 签名登记，生成 8 位验证码；任何人扫码开网页即可复核原图是否被改过 |
| 多人协同 | 建队/队伍码/二维码加入，队长矩形分块或圆形下达搜索区域，队友位置与打点实时同步，观战只读，队长可转让 |
| 培训模式 | 内置 200 点回放剧本 + 解说卡，供学警无真机环境下学习 |
| 主题系统 | 明/暗/跟随系统 + 10 套主题色 + HSL 微调，全局玻璃拟态设计语言 |

## 目录结构

```
android/          Android 客户端（Kotlin + Jetpack Compose + 百度地图 SDK）
  app/src/main/java/com/gakj/weilie/
    core/telephony/   小区/双卡/信号采集与编号语义（CellCodec、SimCellPlanner）
    core/sampling/    采样控制器、会话与导出
    core/photo/       取证照片存储与索引
    core/sync/        协议（WsMsg）与房间客户端
    ui/map/           地图页、数据栏、工具箱、图层、引擎适配（Baidu / Canvas 双实现）
    ui/camera/        取证相机与照片详情
    ui/team/          组队、房间、协同地图、扫码
    ui/expert/        专家面板
    ui/…              大厅、登录、设置、历史、培训、AI 助手
server/           Ktor 协同服务端（房间 WS 同步 + 照片验真网页 + 登记接口）
docs/             研究报告、申报材料、答辩脚本、部署指南、交接说明
tools/            联调探针（Node WebSocket）与模拟器 UI 自动化脚本
dist/             构建产物：Android APK + 服务端 fatJar
```

## 构建

前置：JDK 17、Android SDK（platform 35 / build-tools）、Gradle 8.9。

```powershell
$env:JAVA_HOME='C:\AndroidDev\jdk-17.0.2'
cd android
C:\AndroidDev\gradle-8.9\bin\gradle.bat :app:testDebugUnitTest :app:assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

服务端：

```powershell
cd server
C:\AndroidDev\gradle-8.9\bin\gradle.bat test fatJar
# 产物：server/build/libs/weilie-server-all.jar
```

## 运行服务端

```bash
java -jar weilie-server-all.jar          # 默认 0.0.0.0:8080
# 数据目录通过 WEILIE_DATA_DIR 指定（默认 ./data）
# 关键接口
#   GET  /api/health
#   POST /api/teams                    建房，返回 roomId / joinCode
#   WS   /ws/{roomId}?userId=&name=&role=
#   POST /api/photo/register           照片登记，返回 code / sig
#   GET  /api/photo/verify?code=       验签
#   GET  /v/{code}                     验真网页（扫码打开）
```

客户端默认服务器地址在 `ui/AppGraph.kt` 的 `DEFAULT_SERVER`，多人页也可当场改。

## 已交付产物

- **Android 安装包**：见 [Releases](https://github.com/Moessif/weilie/releases) → `weilie-v0.4.5-debug.apk`（arm64-v8a / x86_64，含百度地图 SDK 原生库，约 77 MB）
  - 体积超过 GitHub 对仓库内单文件的建议上限（50 MB），因此**不入库**，改由 Releases 分发
- `dist/weilie-server-v0.4.5-all.jar`：服务端可执行 fatJar（随源码入库，可直接 `java -jar` 启动）
- `dist/` 仅供放置已构建产物；`dist/*.apk` 已在 `.gitignore` 中排除

## 第三方依赖与注意事项

- 地图：百度地图 Android SDK（`android/app/libs/BaiduLBS_Android.aar` + `jniLibs`）。**AK 与包名/SHA1 绑定需自行在百度控制台配置**，仓库内 `AndroidManifest.xml` 中的 `com.baidu.lbsapi.API_KEY` 仅为开发期占位，正式使用请替换为自己的 Key。
- 相机/扫码：CameraX、ZXing core（二维码生成同样复用 ZXing）。
- 本项目为公安院校教学科研与执法训练用途。使用时须遵守《网络安全法》《个人信息保护法》等法律法规，仅在授权范围内采集网络参数，不得用于侵犯公民隐私或其他非法用途。所有采集数据默认仅存本机。

## 版本轨迹

| 版本 | 要点 |
| --- | --- |
| v0.1 – v0.2 | 大厅/登录/单人地图页骨架，Canvas 自绘地图 → 接入百度地图，玻璃拟态视觉 |
| v0.2.3 – v0.2.4 | 基站编号语义（ECI/NCI 分解、习惯写法）、工具箱长按拖拽自由摆放 |
| v0.3.0 | 取证相机 + 双卡制式层 + 数据栏默认展开 + 标点详情 |
| v0.4.0 | 水印相机（微信式版式）、主题系统、照片在线验真（服务端 + 网页） |
| v0.4.3 – v0.4.4 | 多人协同 P1 修复与端到端联机、首启权限检查页、5G NSA 显示、缩放刻度统一 |
| v0.4.5 | 已连接基站分行显示 5G NR 与 LTE 锚点、工具箱 5 列两行收进十件工具 |
