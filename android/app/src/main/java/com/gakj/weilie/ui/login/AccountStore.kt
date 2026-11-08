package com.gakj.weilie.ui.login

import android.os.Build
import android.provider.Settings
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route

/**
 * 本地账号存储（STRICT_SPEC §1.6）：本期不做服务器校验，
 * 账号 >=4 字符即视为登录成功，全部字段落 weilie_prefs。
 */
object AccountStore {

    private val prefs get() = AppGraph.prefs
    private val ctx get() = AppGraph.ctx

    fun isLoggedIn(): Boolean = prefs.getBoolean(Route.KEY_LOGGED_IN, false)

    fun account(): String = prefs.getString(Route.KEY_LOGIN_USER, "").orEmpty()

    fun org(): String = prefs.getString(Route.KEY_LOGIN_ORG, "").orEmpty()

    /** 昵称：未登录一律「游客」；已登录优先用户设置的昵称，其次登录账号 */
    fun nickname(): String {
        if (!isLoggedIn()) return "游客"
        val n = prefs.getString(Route.PREF_NICKNAME, "").orEmpty()
        return n.ifBlank { account().ifBlank { "游客" } }
    }

    /** 本机设备型号，如 "Google Pixel 7" */
    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** 设备标识（ANDROID_ID） */
    fun deviceId(): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull() ?: "未知"

    /** 登录成功：写登录态、补默认昵称、追加一条登录记录 */
    fun login(user: String) {
        prefs.edit()
            .putBoolean(Route.KEY_LOGGED_IN, true)
            .putString(Route.KEY_LOGIN_USER, user)
            .apply()
        if (prefs.getString(Route.PREF_NICKNAME, "").isNullOrBlank()) {
            prefs.edit().putString(Route.PREF_NICKNAME, user).apply()
        }
        appendLoginRecord()
    }

    /** 追加一条登录记录：时间戳|设备型号，最多保留 50 条 */
    fun appendLoginRecord() {
        val line = "${System.currentTimeMillis()}|${deviceModel()}"
        val old = prefs.getString(Route.KEY_LOGIN_RECORDS, "").orEmpty()
        val lines = old.split("\n").filter { it.isNotBlank() } + line
        prefs.edit()
            .putString(Route.KEY_LOGIN_RECORDS, lines.takeLast(50).joinToString("\n"))
            .apply()
    }

    /** 登录记录列表（时间戳 + 设备），最新在前 */
    fun records(): List<Pair<Long, String>> =
        prefs.getString(Route.KEY_LOGIN_RECORDS, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .map { line ->
                val i = line.indexOf('|')
                if (i > 0) {
                    (line.substring(0, i).toLongOrNull() ?: 0L) to line.substring(i + 1)
                } else {
                    0L to line
                }
            }
            .sortedByDescending { it.first }

    /** 保存资料：昵称 + 组织 */
    fun saveProfile(nick: String, org: String) {
        prefs.edit()
            .putString(Route.PREF_NICKNAME, nick.trim())
            .putString(Route.KEY_LOGIN_ORG, org.trim())
            .apply()
    }

    /**
     * 退出登录：清登录态 + 账号/组织/昵称，
     * 否则大厅会继续显示上一次登录的「您好，xxx」；登录记录保留。
     */
    fun logout() {
        prefs.edit()
            .putBoolean(Route.KEY_LOGGED_IN, false)
            .remove(Route.KEY_LOGIN_USER)
            .remove(Route.KEY_LOGIN_ORG)
            .remove(Route.PREF_NICKNAME)
            .apply()
    }
}
