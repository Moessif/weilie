# 围猎 UI 实现规格（给实现者的唯一事实来源）

## 使命
在 `weilie\android` 工程内实现全部 Compose 界面与导航。工程为 Kotlin 2.0.21 + Compose BOM 2024.10.00 + Material3 + navigation-compose，minSdk 26 / targetSdk 35，包名 `com.gakj.weilie`。

## 第一步必读（全部在 E:\0workspace\dshnext\Marches\GAKJ\weilie\android\）
先读这些文件再动手，API 以文件内实际签名为准：
- app\src\main\java\com\gakj\weilie\core\model\Models.kt（Rat/CellIdentity/SignalData/SampleRecord/CoverageTask/TargetCell/TargetFormat…）
- core\parsing\TaskInputParser.kt（输入解析）
- core\sampling\SamplingController.kt（start/stop、samples/events/snapshot/sampleCount StateFlow）
- core\sync\Protocol.kt 与 core\sync\RoomClient.kt（多人）
- core\ai\AiClient.kt（AiMessage/chat/systemPrompt/sceneAnalysisPrompt/docAnswerPrompt/summarize、AiGuard.check）
- core\store\SessionStore.kt（listSessions/loadSamples/exportCsvFile）
- core\fitting\CoverageFitter.kt（fit(samples, Options) → CoverageResult?）
- core\rating\SignalRating.kt、core\export\Exporters.kt
- app\src\main\AndroidManifest.xml（注意已注册的 service/activity 全限定名——你实现的服务类必须与其一致，或同步修改 Manifest）
- ui\theme\Theme.kt / Glass.kt / Motion.kt（设计系统，必须复用：WeilieTheme、WColors、GlassCard、GlowButton、PulsingDot、SignalBars、StatChip、SectionHeader、Motion）

## 依赖约束
- 只能用 build.gradle.kts 已有依赖；如需 material-icons-extended，往 gradle\libs.versions.toml + app\build.gradle.kts 添加（版本跟 BOM，无需写版本号）。禁止新增其他三方库。
- 不用 Room/DataStore；轻量持久化用 SharedPreferences（文件名固定 "weilie_prefs"）。
- 百度/高德 SDK 一行都不引；地图用 Canvas 自绘实现（见下）。
- 全部代码 Kotlin；避免实验性 API 以外的编译告警即可，必须保证可编译。

## 全局
- MainActivity（包 com.gakj.weilie）：setContent { WeilieTheme { WeilieApp() } }；WeilieApp = Scaffold + NavHost。
- 导航路由：wizard / home / newtask / map/{taskId} / team / teamroom/{roomId} / history / session/{taskId} / settings。转场用 Motion 的时长与 EaseEmphasized（slideInHorizontally+fadeIn 进，反向出）。
- 首启逻辑：SharedPreferences 键 "legal_accepted_v1"（Bool）。false → 固定落在 wizard；同意后才可去 home。

## 屏幕清单（每屏一个文件，放 ui\ 对应子包）
1. ui\wizard\WizardScreen：横向 Pager 3 页（产品功能 / 适用人群与开发者 / 法律声明+承诺勾选）。最后一页 Checkbox「我已阅读并同意法律声明，仅将本工具用于合法执法与教学用途」+ GlowButton「进入围猎」，点击写 prefs 并导航 home（popUpTo wizard）。
2. ui\home\HomeScreen（大厅）：顶部标题「围猎」+ 副标题 + PulsingDot(采集运行时红色/否则灰)；两张大 GlassCard：单人实测、多人协同（各配 icon+一句白话说明）；下方一行三个小卡：历史记录/设置/帮助。点击单人 → newtask；多人 → team。
3. ui\task\NewTaskScreen：大输入框（placeholder 示例：`ECI 123456789`、`LAC 12345 CI 67890`、`31.2304 121.4737 R=800`）；输入变化即调 TaskInputParser.parse，下方玻璃卡实时展示：格式 badge、unifiedCi、eNodeB/CID 拆解（如适用）、参考半径；任务名称输入（默认"任务 yyyyMMdd-HHmm"）；阈值滑条 -95..-125 默认 -110（存任务 Options）；GlowButton「创建并开始」→ 构造 CoverageTask（taskId=UUID 短码）导航 map/{taskId}。解析失败禁用按钮并红字提示。
4. ui\map\SingleMapScreen(taskId)：
   - 进入时若无 AppGraph.currentController(taskId) 则创建 SamplingController 并 start()（经前台服务：调 ui\service 或 service 包下的 SamplingForegroundService.start(context, task)，服务名以 Manifest 为准）；onBackPressedDispatcher 返回键先停采集（对话框确认）。
   - 顶部悬浮数据栏（GlassCard，两半）：上半=SignalBars(SignalRating.level(rsrp)) + RSRP dBm（等宽大字）+ RAT/运营商 chips；下半=目标状态：onTarget→绿色 badge「目标驻留」、targetSeen→青色「邻区可见」、否则灰「目标未出现」+ 采样数/已用时。
   - 地图：CanvasMapEngine（ui\map\MapCanvas.kt）：以样本 bbox 自适应缩放，detectTransformGestures 支持平移缩放；绘制：轨迹折线（WColors.Track）、当前点圆点、目标参考圈（Target 虚线）、hull 填充 0x3300B050、等值线描边 0xFF0066FF；右上角悬浮小卡显示比例与样本数。
   - 覆盖拟合：LaunchedEffect 每 3s：samples.value.size>=8 时 CoverageFitter.fit(threshold=任务阈值) 结果存 remember 状态供地图与置信度显示。
   - 左下工具箱 A1–A4 竖排圆形玻璃按钮：A1 目标信息（弹出目标解析详情 Dialog）；A2 专家数据（ExpertPanel sheet）；A3 采集开关（运行→PulsingDot+「暂停并保存」：调 service stop + controller.stop()；停止→重新 start）；A4 工具 sheet：导出 CSV/GeoJSON/KML（SessionStore.exportCsvFile 或直接 Exporters 写文件到 getExternalFilesDir(exports)，Toast 文件路径+ACTION_SEND 分享）、切基站震动开关（prefs，收集 events 变化→Vibrator 100ms）、AI 助手（AiSheet）、帮助 Dialog。
5. ui\expert\ExpertPanel（ModalBottomSheet）：最新 snapshot+sample 全字段键值表（RAT/MCC/MNC/LAC·TAC/CI 十进制与十六进制/PCI/EARFCN/Band/RSRP/RSRQ/SINR/RSSI/邻区数/邻区 CI 列表/WiFi 前 8：SSID/BSSID/信道/电平），等宽字体；下方「小区切换时间线」LazyColumn（CellChangeEvent.plainText，倒序）。
6. ui\ai\AiSheet：消息列表（气泡玻璃卡，AI 左用户右）+ 底部输入行 + 3 个预设问题 chips（现在信号怎么样？/接下来往哪走？/RSRP 是什么意思？）。发送前 AiGuard.check 返回非空则直接作为 AI 回复显示（本地拒答，不请求）。请求用 AiClient.chat(listOf(AiMessage("system",systemPrompt()),AiMessage("user",prompt)))，AiClient 单例（object AiHolder）配置来自 Settings prefs。发送中显示三个跳点 loading。
7. ui\team\TeamScreen：昵称输入（prefs 记忆）+ 服务器地址输入（默认 http://阿里云IP:8080，prefs 键 "server_url"）+ 两个按钮：创建房间（POST {server}/api/teams {"name":昵称} → 返回 roomId/JoinCode → 导航 teamroom）、输入 6 位房间码加入（GET {server}/api/teams/by-code/{code} → roomId → teamroom）。网络错误红字提示。用独立 HttpClient(CIO) 即可。
8. ui\team\TeamRoomScreen(roomId)：进入即 RoomClient(baseUrl 转 ws://) connect(Hello(roomId,userId=UUID(prefs 记忆),name,role=MEMBER))；地图画布：所有 members 位置点（WColors.Teammate，onTarget 成员高亮圈）、zone 圆/多边形、markers；底部输入行发聊天（RoomClient.chat）；右上角「标记」按钮→两个选项（普通旗标/可疑设备）→ 把屏幕中心打点 Marker；离开时 disconnect。顶部玻璃条显示房间码+连接状态（state flow 文案：已连接/重连中/离线）+成员数。
9. ui\history\HistoryScreen + SessionDetailScreen：列表卡片（标题/目标输入/样本数/起止时间）；详情加载样本→CoverageFitter 拟合→统计卡（样本数/内点/置信度等级与分数/覆盖面积 km²=ConvexHull.area/1e6）+ 地图画布复用 MapCanvas（只读回放）+ 导出按钮组。
10. ui\settings\SettingsScreen：分组玻璃卡：① 地图（百度地图 AK 输入框，键 "baidu_map_key"，暂存）；② AI（key "ai_key" 密码框、baseUrl "ai_base_url" 默认 https://api.deepseek.com、模型 "ai_model" 默认 deepseek-chat）；③ 服务器 "server_url"；④ 权限检查：三行按钮（定位/相机/通知）用 rememberLauncherForActivityResult 请求并显示已授权状态。所有输入即时写 prefs。

## 服务与 AppGraph
- AppGraph（ui\AppGraph.kt，object）：`var currentController: SamplingController?`、`fun ensure(context,taskId,task): SamplingController`、SessionStore 单例、AiClient 单例、prefs 便捷读写。
- SamplingForegroundService：实现 Manifest 已注册的全限定类（先读 Manifest）。ACTION_START_EXTRA_TASK/taskId/title；startForeground 通知渠道 "sampling"（低重要性，POST_NOTIFICATIONS 运行时请求由设置页负责）；onDestroy 停 controller。通知文案：「围猎采集中 · {title}」。

## 质量要求
- 每个 @Composable 文件顶部 import 完整；写完自查一遍 import（特别是 remember/MaterialTheme/alpha/padding）。
- 硬编码颜色一律走 WColors；间距用 4dp 栅格；文案全部简体中文、执法场景语气、不用 emoji（地图标记允许▲⚠等符号字符）。
- 完成后输出：写入的文件清单 + 每个文件一句话说明 + 你自查发现的遗留问题列表（没有就写"无"）。
