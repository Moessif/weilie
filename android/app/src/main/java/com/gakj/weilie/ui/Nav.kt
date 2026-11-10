package com.gakj.weilie.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gakj.weilie.ui.ai.AiSheet
import com.gakj.weilie.ui.history.HistoryScreen
import com.gakj.weilie.ui.history.SessionDetailScreen
import com.gakj.weilie.ui.home.HomeScreen
import com.gakj.weilie.ui.login.LoginScreen
import com.gakj.weilie.ui.login.ProfileScreen
import com.gakj.weilie.ui.map.SingleMapScreen
import com.gakj.weilie.ui.settings.SettingsScreen
import com.gakj.weilie.ui.task.NewTaskScreen
import com.gakj.weilie.ui.team.RoomScreen
import com.gakj.weilie.ui.team.ScanScreen
import com.gakj.weilie.ui.team.TeamRoomScreen
import com.gakj.weilie.ui.team.TeamScreen
import com.gakj.weilie.ui.theme.Motion
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.training.TrainingScreen
import com.gakj.weilie.ui.wizard.WizardScreen

/** 路由常量与偏好键 */
object Route {
    const val WIZARD = "wizard"
    const val HOME = "home"
    const val NEW_TASK = "newtask"
    const val MAP = "map/{taskId}"
    const val TEAM = "team"
    const val TEAM_ROOM = "teamroom/{roomId}"
    const val ROOM = "room/{roomId}"
    const val HISTORY = "history"
    const val SESSION = "session/{taskId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val PROFILE = "profile"
    const val TRAINING = "training"
    const val SCAN = "scan"

    // 偏好键（weilie_prefs）
    const val LEGAL_KEY = "legal_accepted_v1"
    const val PREF_AI_KEY = "ai_key"
    const val PREF_AI_URL = "ai_base_url"
    const val PREF_AI_MODEL = "ai_model"
    const val PREF_SERVER = "server_base"
    const val PREF_BAIDU_AK = "baidu_ak"
    const val PREF_NICKNAME = "nickname"
    const val PREF_VIBRATE = "vibrate_on_cell_change"
    const val PREF_CELL_ALERT = "cell_alert_notice"
    const val PREF_PINNED_TOOLS = "pinned_tools"
    const val PREF_PLACED_TOOLS = "placed_tools"
    const val PREF_SAMPLE_INTERVAL = "sample_interval_ms"

    /** 实测封闭区域图层开关（默认开） */
    const val PREF_AREA_ON = "map_area_on"
    // 实测封闭区域的成面方式：AUTO=高精度凹包，SIMULATED=模拟实测（轨迹点画圆/方连成片）
    const val PREF_AREA_MODE = "map_area_mode"
    const val PREF_AREA_BUFFER_RADIUS = "map_area_buffer_radius"
    const val PREF_AREA_BUFFER_SHAPE = "map_area_buffer_shape"

    /**
     * 单人模式下被用户「从地图移除」的取证标点 id（\u0001 分隔）。
     * 照片本身不删除（仍是证据），只让它的标点不再出现在地图上；
     * 否则下次进入任务从 PhotoStore 回填时又会冒出来（用户反馈「删了还在」）。
     */
    const val PREF_HIDDEN_PHOTO_MARKERS = "hidden_photo_markers"

    /** 多人模式：上一次「定位到队员」的选择（userId），空 = 定位自己 */
    const val PREF_LOCATE_MEMBER = "locate_member_id"

    /** 需求 2：GPS 双重安检门槛（精度 m / 防抖距离 m） */
    const val PREF_GATE_ACCURACY = "gate_accuracy_m"
    const val PREF_GATE_MIN_DIST = "gate_min_dist_m"

    // 外观（主题）
    const val PREF_THEME_MODE = "theme_mode"
    const val PREF_THEME_ACCENT = "theme_accent"
    const val PREF_THEME_HUE = "theme_hue"
    const val PREF_THEME_SAT = "theme_sat"

    // 账号（本地登录态，STRICT_SPEC §1.6）
    const val KEY_LOGGED_IN = "logged_in"
    const val KEY_LOGIN_USER = "login_user"
    const val KEY_LOGIN_ORG = "login_org"
    const val KEY_LOGIN_RECORDS = "login_records"
}

/**
 * 全应用导航根（MainActivity 的 setContent 入口）。
 * 首启向导（legal_accepted_v1 未勾选）→ wizard，否则 home。
 */
@Composable
fun WeilieApp() {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val start = remember {
        if (AppGraph.prefs.getBoolean("legal_accepted_v1", false)) Route.HOME else Route.WIZARD
    }

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier.background(WColors.Bg0),
        enterTransition = {
            fadeIn(tween(Motion.NAV_DURATION)) +
                slideInHorizontally(tween(Motion.NAV_DURATION)) { it / 6 }
        },
        exitTransition = {
            fadeOut(tween(Motion.NAV_DURATION)) +
                slideOutHorizontally(tween(Motion.NAV_DURATION)) { -it / 8 }
        },
        popEnterTransition = {
            fadeIn(tween(Motion.NAV_DURATION)) +
                slideInHorizontally(tween(Motion.NAV_DURATION)) { -it / 8 }
        },
        popExitTransition = {
            fadeOut(tween(Motion.NAV_DURATION)) +
                slideOutHorizontally(tween(Motion.NAV_DURATION)) { it / 6 }
        },
    ) {
        composable(Route.WIZARD) {
            WizardScreen(onDone = {
                AppGraph.prefs.edit().putBoolean("legal_accepted_v1", true).apply()
                nav.navigate(Route.HOME) {
                    popUpTo(Route.WIZARD) { inclusive = true }
                }
            })
        }
        composable(Route.HOME) {
            HomeScreen(
                onNewTask = { nav.navigate(Route.NEW_TASK) },
                onTeam = { nav.navigate(Route.TEAM) },
                onHistory = { nav.navigate(Route.HISTORY) },
                onSettings = { nav.navigate(Route.SETTINGS) },
                onAvatar = {
                    if (AppGraph.prefs.getBoolean(Route.KEY_LOGGED_IN, false)) {
                        nav.navigate(Route.PROFILE)
                    } else {
                        nav.navigate(Route.LOGIN)
                    }
                },
                onContinueTask = { id -> nav.navigate("map/$id") },
                onTraining = { nav.navigate(Route.TRAINING) },
            )
        }
        composable(Route.LOGIN) {
            LoginScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable(Route.PROFILE) {
            ProfileScreen(onBack = { nav.popBackStack() })
        }
        composable(Route.NEW_TASK) {
            NewTaskScreen(
                onBack = { nav.popBackStack() },
                onCreated = { taskId -> nav.navigate("map/$taskId") { popUpTo(Route.HOME) } },
            )
        }
        composable(
            Route.MAP,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            SingleMapScreen(
                taskId = taskId,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Route.SETTINGS) },
                onOpenTeam = { nav.navigate(Route.TEAM) },
            )
        }
        composable(Route.TEAM) {
            TeamScreen(
                onBack = { nav.popBackStack() },
                onScan = { nav.navigate(Route.SCAN) },
                onJoined = { roomId -> nav.navigate("room/$roomId") { popUpTo(Route.HOME) } },
            )
        }
        composable(Route.SCAN) {
            ScanScreen(
                onBack = { nav.popBackStack() },
                onScanned = { code ->
                    AppGraph.lastScanCode = code
                    nav.popBackStack()
                },
            )
        }
        composable(
            Route.ROOM,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId").orEmpty()
            RoomScreen(
                roomId = roomId,
                onBack = { nav.popBackStack() },
                onEnterTask = { nav.navigate("teamroom/$roomId") },
            )
        }
        composable(
            Route.TEAM_ROOM,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId").orEmpty()
            TeamRoomScreen(roomId = roomId, onBack = { nav.popBackStack() })
        }
        composable(Route.HISTORY) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenSession = { taskId -> nav.navigate("session/$taskId") },
            )
        }
        composable(
            Route.SESSION,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            SessionDetailScreen(taskId = taskId, onBack = { nav.popBackStack() })
        }
        composable(Route.TRAINING) {
            TrainingScreen(onBack = { nav.popBackStack() })
        }
        composable(Route.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
