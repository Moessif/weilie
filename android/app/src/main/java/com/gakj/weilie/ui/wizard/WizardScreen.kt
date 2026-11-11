package com.gakj.weilie.ui.wizard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.gakj.weilie.R
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.common.AnimatedDot
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.hasPerm
import com.gakj.weilie.ui.map.BaiduBootstrap
import com.gakj.weilie.ui.theme.Eyebrow
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.Hairline.hairline
import com.gakj.weilie.ui.theme.Radius
import com.gakj.weilie.ui.theme.Rhythm
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.glass
import kotlinx.coroutines.launch

/** 向导总页数：产品功能 / 适用人群 / 法律声明 / 权限检查 */
private const val TOTAL_STEPS = 4
private const val PAGE_LEGAL = 2
private const val PAGE_PERMISSION = 3

/**
 * 首启向导：4 页横滑（产品功能 / 适用人群与开发者 / 法律声明+承诺勾选 / 权限检查）。
 * 权限页逐项申请定位/电话/相机/通知（后台定位可选），全部处理完后「进入围猎」；
 * 「先跳过，稍后设置」同样写 legal_accepted_v1 并进入大厅，保证不阻塞首启。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WizardScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { TOTAL_STEPS })
    val scope = rememberCoroutineScope()
    var agreed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        Spacer(Modifier.statusBarsPadding().height(20.dp))
        // 进度指示点
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(TOTAL_STEPS) { i -> AnimatedDot(active = pagerState.currentPage >= i) }
        }
        Spacer(Modifier.height(12.dp))

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> PageIntro()
                1 -> PageAudience()
                PAGE_LEGAL -> PageLegal(agreed) { agreed = it }
                else -> PagePermission(onFinish = onDone)
            }
        }

        // 底部操作区：前两页「下一步」；法律声明页勾选后进入权限检查；权限页按钮在页面内
        if (pagerState.currentPage < PAGE_LEGAL) {
            GlowButton(
                text = if (pagerState.currentPage == 0) "开始了解" else "下一步",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.screenH),
            ) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }
            Spacer(Modifier.navigationBarsPadding().height(28.dp))
        } else if (pagerState.currentPage == PAGE_LEGAL) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = { agreed = it },
                    colors = CheckboxDefaults.colors(checkedColor = WColors.Primary, checkmarkColor = WColors.Bg0),
                )
                Text(
                    "我已阅读并同意法律声明，仅将本工具用于合法执法与教学用途",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
            }
            Spacer(Modifier.height(10.dp))
            GlowButton(
                text = "下一步：权限检查",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.screenH),
                enabled = agreed,
            ) {
                // 保留原「进入围猎」的合规动作：写同意标记 + 初始化地图 SDK
                AppGraph.prefs.edit().putBoolean(Route.LEGAL_KEY, true).apply()
                BaiduBootstrap.init(AppGraph.ctx)
                scope.launch { pagerState.animateScrollToPage(PAGE_PERMISSION) }
            }
            Spacer(Modifier.navigationBarsPadding().height(28.dp))
        } else {
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

/** 第 1 页：产品功能 */
@Composable
private fun PageIntro() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Rhythm.section))
        Eyebrow("围猎 · FIELD SCOUT")
        Spacer(Modifier.height(Space.x2))
        Text("围猎", style = MaterialTheme.typography.displaySmall, color = WColors.OnBg)
        Spacer(Modifier.height(Space.x2))
        Text("蜂窝网络覆盖实测与协同定位工具", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
        Spacer(Modifier.height(Rhythm.section))
        FeatureRow(Icons.Rounded.CenterFocusStrong, "目标参数智能解析", "ECI / NCI / LAC+CI / 经纬度多格式一键识别")
        Spacer(Modifier.height(Space.x2))
        FeatureRow(Icons.Rounded.NetworkCheck, "1Hz 采样与信号评级", "RSRP/RSRQ/SINR 逐秒记录，六档评级直观呈现")
        Spacer(Modifier.height(Space.x2))
        FeatureRow(Icons.Rounded.Layers, "覆盖拟合与等值线", "凹包边界 + 插值等值线，覆盖范围一眼看清")
        Spacer(Modifier.height(Space.x2))
        FeatureRow(Icons.Rounded.Groups, "多人协同与 AI 助手", "队友位置实时共享，内置通信技术问答助手")
        Spacer(Modifier.height(Rhythm.section))
        Eyebrow("三步上手")
        Spacer(Modifier.height(Space.x3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            listOf("01" to "建任务", "02" to "现场走测", "03" to "查看覆盖").forEach { (num, label) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(Radius.mdShape)
                        .background(WColors.ChipBg)
                        .hairline(Radius.mdShape)
                        .padding(vertical = Space.x3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        num,
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.Primary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(Space.x1))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = WColors.OnBg)
                }
            }
        }
        Spacer(Modifier.height(Space.x3))
        Text(
            "普通手机即可完成覆盖实测，无需专业扫频设备。",
            style = MaterialTheme.typography.bodySmall,
            color = WColors.OnBgDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Rhythm.section))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(WColors.ChipBg, CircleShape)
                    .hairline(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = title, tint = WColors.Primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(Space.x3))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
            }
        }
    }
}

/** 第 2 页：适用人群与开发者 */
@Composable
private fun PageAudience() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screenH),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(Space.x6))
        Text("适用人群", style = MaterialTheme.typography.headlineMedium, color = WColors.OnBg)
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                Text("· 公安院校教学科研", style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg)
                Spacer(Modifier.height(6.dp))
                Text("· 一线民警执法培训与演练", style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg)
                Spacer(Modifier.height(6.dp))
                Text("· 无线网络优化课程实训", style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("开发者与版本", style = MaterialTheme.typography.headlineMedium, color = WColors.OnBg)
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                Text("「围猎」项目组 · 教学科研版本 v${com.gakj.weilie.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg)
                Spacer(Modifier.height(6.dp))
                Text(
                    "全部数据仅存储于本机应用目录，可随时导出或删除，不做云端归集。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
            }
        }
    }
}

/** 第 3 页：法律声明 + 承诺勾选 */
@Composable
private fun PageLegal(agreed: Boolean, onAgreed: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screenH),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("法律声明", style = MaterialTheme.typography.headlineMedium, color = WColors.OnBg)
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.legal_notice), style = MaterialTheme.typography.bodyLarge, color = WColors.OnBg)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (agreed) "已确认承诺，感谢配合" else "请在充分理解上述内容后勾选下方承诺",
            style = MaterialTheme.typography.labelSmall,
            color = if (agreed) WColors.Success else WColors.OnBgDim,
        )
    }
}

/**
 * 第 4 页：权限检查。
 *
 * 5 行权限卡（定位/电话/相机/通知/后台定位），已授予显示绿色「已授权」，未授予点「授权」
 * 直接弹系统框；后台定位在 Android 11+ 只能跳应用设置页，标记为「可选」。
 * 「全部授予并进入围猎」按缺失项一次性发起（系统依次弹框），流程结束即完成向导；
 * 「先跳过，稍后设置」同样写同意标记并进入大厅。
 */
@Composable
private fun PagePermission(onFinish: () -> Unit) {
    val ctx = LocalContext.current

    // 权限申请/从设置返回后 +1，重新读取系统权限状态
    var tick by remember { mutableIntStateOf(0) }
    val locOk = remember(tick) { hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val phoneOk = remember(tick) { hasPerm(ctx, Manifest.permission.READ_PHONE_STATE) }
    val camOk = remember(tick) { hasPerm(ctx, Manifest.permission.CAMERA) }
    val notifOk = remember(tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }
    val bgShown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val bgOk = remember(tick) {
        !bgShown || hasPerm(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val readyCount = listOf(locOk, phoneOk, camOk, notifOk, bgOk).count { it }

    // 可用性自检结果（授予后自动刷新，也可点「自检」手动刷新）
    var locCheck by remember { mutableStateOf("") }
    var phoneCheck by remember { mutableStateOf("") }
    var camCheck by remember { mutableStateOf("") }
    var notifCheck by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        locCheck = if (locOk) checkLocation(ctx) else ""
        phoneCheck = if (phoneOk) checkPhone(ctx) else ""
        camCheck = checkCamera(ctx)
        notifCheck = checkNotifications(ctx)
    }

    /** 完成向导：写 legal_accepted_v1 → 初始化百度地图 → 进大厅（两处按钮共用） */
    fun finishNow() {
        AppGraph.prefs.edit().putBoolean(Route.LEGAL_KEY, true).apply()
        BaiduBootstrap.init(AppGraph.ctx)
        onFinish()
    }

    // 单个权限申请：回调只刷新状态
    val oneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        tick++
    }
    // 「全部授予」：系统依次弹缺失的前台权限，流程结束后直接完成向导（部分拒绝也可稍后到设置补）
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        tick++
        finishNow()
    }

    fun requestOne(permission: String) {
        if (hasPerm(ctx, permission)) tick++ else oneLauncher.launch(permission)
    }

    fun requestAllForeground() {
        val missing = foregroundMissing(ctx)
        if (missing.isEmpty()) finishNow() else batchLauncher.launch(missing.toTypedArray())
    }

    fun requestBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 后台定位只能到应用详情页手动开启
            runCatching {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"),
                    ),
                )
            }
        } else {
            oneLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screenH),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("权限检查", style = MaterialTheme.typography.headlineMedium, color = WColors.OnBg)
        Spacer(Modifier.height(6.dp))
        Text(
            "围猎需要以下权限才能正常采集与取证，逐项确认即可进入",
            style = MaterialTheme.typography.bodyMedium,
            color = WColors.OnBgDim,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已就绪 $readyCount/5",
                style = MaterialTheme.typography.labelLarge,
                color = if (readyCount == 5) WColors.Success else WColors.OnBgDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                "刷新",
                style = MaterialTheme.typography.labelMedium,
                color = WColors.Primary,
                modifier = Modifier.clickable { tick++ },
            )
        }
        Spacer(Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Rounded.MyLocation,
            title = "精确定位",
            purpose = "记录每个采样点的经纬度；需选择「仅在使用应用时允许」并打开精确位置",
            granted = locOk,
            checkText = locCheck.ifBlank { "授权后可检测" },
            onRequest = { requestOne(Manifest.permission.ACCESS_FINE_LOCATION) },
            onCheck = { locCheck = checkLocation(ctx) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            icon = Icons.Rounded.SimCard,
            title = "电话状态",
            purpose = "读取双卡小区编号、频点与信号参数；不读号码/短信/通讯录",
            granted = phoneOk,
            checkText = phoneCheck.ifBlank { "授权后可检测" },
            onRequest = { requestOne(Manifest.permission.READ_PHONE_STATE) },
            onCheck = { phoneCheck = checkPhone(ctx) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            icon = Icons.Rounded.PhotoCamera,
            title = "相机",
            purpose = "现场取证拍照，照片会烧入水印",
            granted = camOk,
            checkText = camCheck,
            onRequest = { requestOne(Manifest.permission.CAMERA) },
            onCheck = { camCheck = checkCamera(ctx) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            icon = Icons.Rounded.Notifications,
            title = "通知",
            purpose = "采集常驻通知与切基站提醒",
            granted = notifOk,
            checkText = notifCheck,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestOne(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    tick++
                }
            },
            onCheck = { notifCheck = checkNotifications(ctx) },
        )
        if (bgShown) {
            Spacer(Modifier.height(10.dp))
            PermissionCard(
                icon = Icons.Rounded.Settings,
                title = "后台定位",
                purpose = "锁屏后仍持续记录轨迹；未授予不影响基础采集",
                granted = bgOk,
                optional = true,
                checkText = if (bgOk) "后台定位已授权" else "可选：未授予不影响基础采集，可在系统设置开启",
                checkLabel = "去设置",
                onRequest = { requestBackground() },
                onCheck = { requestBackground() },
            )
        }

        Spacer(Modifier.height(20.dp))
        GlowButton(
            text = "全部授予并进入围猎",
            modifier = Modifier.fillMaxWidth(),
        ) { requestAllForeground() }
        Spacer(Modifier.height(12.dp))
        Text(
            "先跳过，稍后设置",
            style = MaterialTheme.typography.labelLarge,
            color = WColors.OnBgDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { finishNow() }
                .padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(Space.x6))
    }
}

/** 一行权限卡：图标 + 名称 + 用途 + 右侧授权状态；下方一行小字可用性自检 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    purpose: String,
    granted: Boolean,
    checkText: String,
    onRequest: () -> Unit,
    onCheck: (() -> Unit)? = null,
    optional: Boolean = false,
    checkLabel: String = "自检",
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .glass(shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = title, tint = WColors.Primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                        if (optional) {
                            Spacer(Modifier.width(6.dp))
                            Text("可选", style = MaterialTheme.typography.labelSmall, color = WColors.OnBgDim)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(purpose, style = MaterialTheme.typography.bodySmall, color = WColors.OnBgDim)
                }
                Spacer(Modifier.width(8.dp))
                when {
                    granted -> Text("已授权", style = MaterialTheme.typography.labelLarge, color = WColors.Success)
                    optional -> Text("未授予", style = MaterialTheme.typography.labelLarge, color = WColors.OnBgDim)
                    else -> GlassTextButton(text = "授权", onClick = onRequest)
                }
            }
            if (checkText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "自检：$checkText",
                        style = MaterialTheme.typography.labelSmall,
                        color = WColors.OnBgDim,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCheck != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            checkLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.Primary,
                            modifier = Modifier
                                .clickable { onCheck() }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------- 权限与可用性自检 ----------

/** 缺失的前台运行时权限（不含可选的 ACCESS_BACKGROUND_LOCATION） */
private fun foregroundMissing(ctx: Context): List<String> {
    val list = ArrayList<String>(4)
    if (!hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (!hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)) {
        list += Manifest.permission.READ_PHONE_STATE
    }
    if (!hasPerm(ctx, Manifest.permission.CAMERA)) {
        list += Manifest.permission.CAMERA
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list
}

/** 定位自检：定位服务开关 + 最近一次定位结果 */
private fun checkLocation(ctx: Context): String = runCatching {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return@runCatching "无法访问定位服务"
    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return@runCatching "定位服务未开启，请到系统设置打开"
    }
    val last = runCatching {
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }.getOrNull()
    if (last != null) "定位服务已开启" else "已授权，等待首次定位"
}.getOrElse { "定位自检失败：${it.message ?: "未知原因"}" }

/** 电话自检：已授权时读订阅数，判断是否检测到 SIM 卡 */
private fun checkPhone(ctx: Context): String = runCatching {
    val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
    val count = sm?.activeSubscriptionInfoCount ?: 0
    if (count > 0) "检测到 $count 张卡" else "未检测到 SIM 卡"
}.getOrElse { "电话状态读取失败：${it.message ?: "请确认已授权"}" }

/** 相机自检：本机是否有可用相机硬件 */
private fun checkCamera(ctx: Context): String =
    if (ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
        "相机可用"
    } else {
        "本机无相机"
    }

/** 通知自检：系统通知开关是否打开 */
private fun checkNotifications(ctx: Context): String = runCatching {
    if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) "通知可用" else "通知被关闭"
}.getOrElse { "通知状态读取失败" }
