# 围猎（WeiLie）· 普通手机基站覆盖实测与搜捕协同系统

面向「基层民警无专业路测设备、仅有一部普通手机」的场景，用手机自身的蜂窝小区信息 + GNSS 定位，
通过**移动打点**反推目标基站的真实覆盖范围，并把结果共享给队友，用于搜捕、找人、清查等任务。

> 参赛项目：中国人民公安大学第三届学生科技应用创新大赛 · 公安网络通信与移动应用关键技术赛道
> 当前版本 **v0.11.2**（Android，versionCode 25）

---

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 单人覆盖实测 | 输入目标小区编号（ECI / NCI / LAC+CI / 经纬度），边走边打点，实时生成轨迹、凹包、覆盖等值线与拟合置信度 |
| 打点三态 | 主区命中＝实心点，邻区命中（NCI/PCI 指纹匹配）＝彩色实心点（不同基站不同色），脱离＝空心点（只画轨迹、不参与面积） |
| GPS 双重安检 | 精度差 > 阈值直接跳过并提示；与上点距离 < 阈值自动合并，避免原地抖动堆点；阈值可在设置里调 |
| 面积两种成面 | 高精度凹包（Alpha Shape，点太少退化为沿线带）／模拟实测区域（轨迹点画圆或方块、大小 5–200m 可调、沿轨迹融合成片） |
| 信号热力图 | RSRP 插值扩散成连续热力层，强弱阈值、网格间隔、扩散半径、透明度可调，独立开关 |
| 理论覆盖区 | 输入经纬度/方位角/距离，自动画全向圆、扇形或橙色 TA 距离环带，与实测区求交 |
| 双卡双制式 | 4G/5G（SA 取 NR 服务小区、NSA 显示锚点）、按数据卡过滤 PLMN 避免混入别家小区，NCI 习惯写法 `5677244420(1386046-4)` |
| 基站库图层 | 接入 OpenCelliD 瓦片缓存（服务端聚合），地图上显示站址、方位角与覆盖扇区，可按基站 / 矩形 / 环带下达任务区 |
| 多人协同 | 建队/队伍码/二维码加入，队长用矩形分块、圆形、按基站扇区或**自由绘制多边形**下达区域，队友位置与打点实时同步，观战只读，队长可转让 |
| 自由绘制范围 | 单指像画画一样拖出范围（实时显示轨迹线与面积），双指拖动地图、捏合缩放，可撤销/重画/确认，并按队员分配 |
| 标点与导航 | 队长与队员都可在地图任意位置标点、命名、设定可见范围，队友一键导航到该点；取证标点可删除（本机/队长权限） |
| 取证相机 | 类水印相机版式（大字时间 / 日期 / 地址 / 蜂窝水印烧入 JPG），5 类取证类型（普通/隐蔽摄像头/可疑摄像头/可疑设备/其他）在地图用不同 emoji 图标区分 |
| 影像验真 | 照片 SHA-256 + 服务端 HMAC 签名登记，生成 8 位验证码；跨设备也能用验证码远程查看登记信息，扫码开网页复核原图是否被改过 |
| 静默跟踪 | 前台服务 + 震动提示（主区/邻区/脱离三种节奏，强度可调），不发声不亮屏，状态写日志，明显停止入口 |
| 数据导出 | CSV / GeoJSON / KML，轨迹照片导出可带真实百度底图与各类覆盖图层；历史任务支持长按/多选/全部删除 |
| 培训模式 | 内置 200 点回放剧本 + 解说卡，供学警无真机环境下学习 |
| 主题系统 | 深色主题（v0.11.0 起固定深色）+ 10 套强调色 + HSL 微调，全局玻璃拟态设计语言 |

## 目录结构

```
android/          Android 客户端（Kotlin + Jetpack Compose + 百度地图 SDK）
  app/src/main/java/com/gakj/weilie/
    core/telephony/   小区/双卡/信号采集与编号语义（CellCodec、SimCellPlanner、NetJudge）
    core/sampling/    采样控制器、会话与导出
    core/fitting/     GPS 安检与卡尔曼、Alpha Shape 凹包、模拟区域缓冲、热力与理论覆盖、等值线
    core/cellmap/     基站库（OpenCelliD）查询、扇区与环带几何
    core/tracking/    静默跟踪判定逻辑
    core/photo/       取证照片存储与索引
    core/verify/      照片验真（本地 + 服务端登记/查询）
    core/sync/        协议（WsMsg）与房间客户端
    service/          静默跟踪前台服务
    ui/map/           地图页、数据栏、工具箱、图层、引擎适配（Baidu / Canvas 双实现）
    ui/camera/        取证相机与照片详情（含跨设备远程记录）
    ui/team/          组队、房间、协同地图、扫码
    ui/…              大厅、登录、设置、历史、培训、AI 助手
server/           Ktor 协同服务端（房间 WS 同步 + 照片验真网页 + 登记接口 + 基站库缓存）
docs/             研究报告、申报材料、答辩脚本、各版本交付说明
tools/            联调探针（Node WebSocket）、真机 UI 自动化与截图取坐标脚本
dist/             已构建的服务端 fatJar（APK 走 Releases）
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
#   GET  /api/photo/verify?code=       验签 / 跨设备查询登记信息
#   GET  /v/{code}                     验真网页（扫码打开）
#   GET  /api/cells/near|cell|stats     基站库查询（OpenCelliD 瓦片缓存）
```

客户端默认服务器地址在 `ui/AppGraph.kt` 的 `DEFAULT_SERVER`，多人页也可当场改。

## 已交付产物

- **Android 安装包**：见 [Releases](https://github.com/Moessif/weilie/releases) → `weilie-v0.11.2-debug.apk`（arm64-v8a / x86_64，含百度地图 SDK 原生库，约 78 MB）
  - 体积超过 GitHub 对仓库内单文件的建议上限（50 MB），因此**不入库**，改由 Releases 分发
- `dist/weilie-server-v0.11.2-all.jar`：服务端可执行 fatJar（随源码入库，可直接 `java -jar` 启动）
- `dist/` 仅供放置已构建产物；`dist/*.apk` 已在 `.gitignore` 中排除

## 战报（全版本更新记录）

- [完整版战报（v0.1 → v0.11.2，26 个版本逐版记录）](docs/战报-v0.1-v0.11.2-完整版.md)
- 长图海报：[docs/assets/战报-v0.1-v0.11.2-完整版.png](docs/assets/战报-v0.1-v0.11.2-完整版.png)
- 生成脚本：[tools/make_battle_report_full.py](tools/make_battle_report_full.py)

## 第三方依赖与注意事项

- 地图：百度地图 Android SDK（`android/app/libs/BaiduLBS_Android.aar` + `jniLibs`）。**AK 与包名/SHA1 绑定需自行在百度控制台配置**，仓库内 `AndroidManifest.xml` 中的 `com.baidu.lbsapi.API_KEY` 仅为开发期占位，正式使用请替换为自己的 Key。
- 基站库：OpenCelliD（CC BY-SA 4.0）。Key 只放在服务端（`data/cells/opencellid.key`），不随 APK 分发。
- 相机/扫码：CameraX、ZXing core（二维码生成同样复用 ZXing）。
- 本项目为公安院校教学科研与执法训练用途。使用时须遵守《网络安全法》《个人信息保护法》等法律法规，仅在授权范围内采集网络参数，不得用于侵犯公民隐私或其他非法用途。所有采集数据默认仅存本机。

## 版本轨迹

| 版本 | 要点 |
| --- | --- |
| v0.1 – v0.2 | 大厅/登录/单人地图页骨架，Canvas 自绘地图 → 接入百度地图，玻璃拟态视觉 |
| v0.2.3 – v0.2.4 | 基站编号语义（ECI/NCI 分解、习惯写法）、工具箱长按拖拽自由摆放 |
| v0.3.0 | 取证相机 + 双卡制式层 + 数据栏默认展开 + 标点详情 |
| v0.4.0 | 水印相机（微信式版式）、主题系统、照片在线验真（服务端 + 网页） |
| v0.4.3 – v0.4.5 | 多人协同修复与端到端联机、首启权限检查、5G NSA 显示、工具箱十件工具一屏 |
| v0.5.0 – v0.5.2 | 打点三态、GPS 双安检 + 卡尔曼、凹包面积、热力图、理论覆盖区、带底图导出、静默跟踪、多人点选/分范围/导航；闪退修复 |
| v0.6.x | 基站库图层（OpenCelliD）、按基站扇区/环带划区；CV 卡尔曼打点、服务小区徽标；历史记录删除 |
| v0.7.0 | 参数规格校验、目标清单、派出所白话说明气泡、标点命名/可见性/删除、震动强度 |
| v0.8.x | 基站数据层重构（SA/NSA 判定、按数据卡 + PLMN 过滤）；真机五问修复（标点删除、名称标签、默认面积、邻区口径、巨点） |
| v0.9.x | 多人自由绘制范围（单指画/双指拖/撤销重画完成/按队员分配）、跨设备取证记录（photoCode）、单人删标点即时生效 |
| v0.10.0 | 七项真机反馈：拍照标点删除+二次确认、命中热区收紧、多人叠加图层、浅色主题可读性、任务区以自身定位为中心、定位到队员、取证类型 emoji 图标 |
| v0.11.x | 自由绘制实时轨迹与面积、拍照标点按任务隔离、固定深色主题；双指手势重写（起点锚定 + 投影实测）；模拟实测区域（圆/方形缓冲成面）与进图巨点修复 |
