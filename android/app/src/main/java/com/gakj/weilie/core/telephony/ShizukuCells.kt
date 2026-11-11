package com.gakj.weilie.core.telephony

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gakj.weilie.core.model.CellIdentity
import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SignalData
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 用 Shizuku（shell 身份，无需 root）读取系统完整的 CellInfo 列表。
 *
 * 背景：Android 10+ 对普通应用屏蔽了邻区；很多 ROM（HyperOS/ColorOS 等）连
 * `getAllCellInfo()` 都只返回驻留小区。Shizuku 授权后我们以 `shell` 身份执行
 * `dumpsys telephony.registry`，其中 `mCellInfo=[...]` 带全部小区（含未注册邻区）
 * 与测量值，足以支撑「邻区命中」判定。
 *
 * 设计要点：
 * - 未安装 / 未运行 / 未授权时全部返回 null/空，调用方自动回退到普通 API，不报错；
 * - 解析是纯字符串处理（可单元测试），不依赖特权 binder 接口，系统升级容错性更好；
 * - 读取放 IO 线程，失败静默降级（不影响主区采集）。
 */
object ShizukuCells {

    /** 授权请求码：Shizuku 回调里按它匹配，避免与其它请求串台 */
    const val REQUEST_CODE = 0x5A17

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    const val DUMP_CMD = "dumpsys telephony.registry"

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isAuthorized(): Boolean = isBinderAlive() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 是否安装了 Shizuku（未装时引导去装，不弹授权） */
    /** Shizuku 服务端版本号（诊断用；未运行返回 -1） */
    fun versionCode(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    /** Shizuku 服务的 uid（shell=2000，root=0；诊断用） */
    fun serviceUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun requestPermission(): Boolean = runCatching {
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    fun addResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removeResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /** 打开 Shizuku App（未运行服务时让用户自己点「启动」） */
    fun openShizukuApp(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 最近一次读取的诊断信息。设置页展示它，便于真机排查
     * （授权了但读不到时，先看这里：是否拿到 mCellInfo、解析出几条、错在哪）。
     */
    data class LastRead(
        val atEpochMs: Long,
        val cellCount: Int,
        val neighborCount: Int,
        val hasCellInfoTag: Boolean,
        val rawChars: Int,
        val error: String?,
    )

    @Volatile
    var lastRead: LastRead? = null
        private set

    private fun record(at: Long, text: String?, cells: Int, error: String?, neighborCount: Int = 0) {
        lastRead = LastRead(
            atEpochMs = at,
            cellCount = cells,
            neighborCount = neighborCount,
            hasCellInfoTag = text?.contains("mCellInfo") == true,
            rawChars = text?.length ?: 0,
            error = error,
        )
    }

    /**
     * 读取并解析一次 telephony.registry。阻塞调用，必须在 IO 线程。
     * @return 解析失败（无权限/进程异常/超时）返回 null，调用方保留上一次结果
     */
    fun readRegistry(): List<RegCell>? {
        val startedAt = System.currentTimeMillis()
        if (!isAuthorized()) {
            record(startedAt, null, 0, "未授权")
            return null
        }
        var process: Process? = null
        return try {
            process = exec(arrayOf("sh", "-c", DUMP_CMD)) ?: run {
                record(startedAt, null, 0, "无法创建 shell 进程")
                return null
            }
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                record(startedAt, text, 0, "命令超时")
                return null
            }
            val cells = parseDump(text)
            record(
                at = startedAt,
                text = text,
                cells = cells.size,
                error = when {
                    cells.isNotEmpty() -> null
                    !text.contains("mCellInfo") -> "输出里没有 mCellInfo"
                    else -> "mCellInfo 存在但解析为 0 条"
                },
                neighborCount = cells.count { !it.registered },
            )
            cells.ifEmpty { null }
        } catch (t: Throwable) {
            record(startedAt, null, 0, "${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            null
        } finally {
            runCatching { process?.destroy() }
        }
    }

    /**
     * 以 shell 身份执行命令。Shizuku 13 把 `Shizuku.newProcess` 设为 private，
     * 官方用法是 `ShizukuBinderWrapper` + 系统服务；这里只需要「跑一条 shell 命令」，
     * 反射调用该私有静态方法更直接（它在 Shizuku 服务端以 shell uid 创建进程）。
     */
    private fun exec(cmd: Array<String>): Process? = runCatching {
        val method = newProcessMethod ?: return null
        method.invoke(null, cmd, null, null) as? Process
    }.getOrNull()

    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * dumpsys 文本里的一条小区。
     * @param registered 是否是本机驻留（YES）
     */
    data class RegCell(
        val rat: Rat,
        val ci: Long?,
        val pci: Int?,
        val arfcn: Int?,
        val mcc: String?,
        val mnc: String?,
        val tac: Long?,
        val registered: Boolean,
        val rsrp: Int?,
        val rsrq: Int?,
        val sinr: Int?,
    ) {
        /** 转成 App 统一的小区标识；band 由 ARFCN 推算（未知为 null） */
        fun identity(): CellIdentity = CellIdentity(
            rat = rat,
            mcc = mcc,
            mnc = mnc,
            lacOrTac = tac,
            ci = ci,
            pci = pci,
            arfcn = arfcn,
            band = bandOf(rat, arfcn),
            isRegistered = registered,
        )

        fun signal(): SignalData = SignalData(rsrp = rsrp, rsrq = rsrq, sinr = sinr)

        companion object {
            fun bandOf(rat: Rat, arfcn: Int?): Int? {
                if (arfcn == null) return null
                val label = when (rat) {
                    Rat.LTE -> BandTable.lteBand(arfcn)
                    Rat.NR -> BandTable.nrBand(arfcn)
                    else -> "-"
                }
                return label.removePrefix("B").removePrefix("N").toIntOrNull()
            }
        }
    }

    // ---------- 解析（纯函数，可单测） ----------

    private val BLOCK_START = Regex("""CellInfo(Gsm|Wcdma|Cdma|Lte|Nr|Tdscdma)\s*:\s*\{""")

    private val REGISTERED = Regex("""mRegistered\s*=\s*(YES|NO)""")

    private fun field(name: String): Regex = Regex("""\b$name\s*=\s*(-?\d+|null)""")

    private val CI = field("mCi")
    private val NCI = field("mNci")
    private val PCI = field("mPci")
    private val EARFCN = field("mEarfcn")
    private val NR_ARFCN = field("mNrArfcn")
    private val MCC = field("mMcc")
    private val MNC = field("mMnc")
    private val TAC = field("mTac")

    // 测量值：LTE/NR 字段名不同；取绝对值后过大（Int.MAX_VALUE / -2147483648）视为缺失
    private val LTE_RSRP = Regex("""\brsrp\s*=\s*(-?\d+)""")
    private val LTE_RSRQ = Regex("""\brsrq\s*=\s*(-?\d+)""")
    private val LTE_SINR = Regex("""\brssnr\s*=\s*(-?\d+)""")
    private val NR_RSRP = Regex("""\bssRsrp\s*=\s*(-?\d+)""")
    private val NR_RSRQ = Regex("""\bssRsrq\s*=\s*(-?\d+)""")
    private val NR_SINR = Regex("""\bssSinr\s*=\s*(-?\d+)""")

    /**
     * 从 `dumpsys telephony.registry` 文本中提取全部小区。
     * 每条记录从 `CellInfoXxx:{` 开始，按花括号配平截取（内部还嵌了 CellIdentity/CellSignalStrength 两级）。
     */
    fun parseDump(text: String): List<RegCell> {
        if (!text.contains("mCellInfo")) return emptyList()
        val out = ArrayList<RegCell>()
        var from = 0
        while (true) {
            val m = BLOCK_START.find(text, from) ?: break
            val open = text.indexOf('{', m.range.first)
            if (open < 0) break
            val end = balancedEnd(text, open)
            if (end < 0) break
            val body = text.substring(open + 1, end)
            val blockText = m.value.substringBefore('{')
            val rat = when {
                blockText.contains("Nr", ignoreCase = true) -> Rat.NR
                blockText.contains("Lte", ignoreCase = true) -> Rat.LTE
                blockText.contains("Wcdma", ignoreCase = true) -> Rat.WCDMA
                blockText.contains("Gsm", ignoreCase = true) -> Rat.GSM
                blockText.contains("Cdma", ignoreCase = true) -> Rat.CDMA
                blockText.contains("Tdscdma", ignoreCase = true) -> Rat.TDSCDMA
                else -> Rat.UNKNOWN
            }
            val registered = REGISTERED.find(body)?.groupValues?.get(1)?.equals("YES", ignoreCase = true) == true
            // NCI 是 36bit、ECI 是 28bit，都不能用 Int 解析（NCI 会溢出成 null）
            val ci = if (rat == Rat.NR) firstLong(NCI, body) ?: firstLong(CI, body) else firstLong(CI, body)
            val arfcn = if (rat == Rat.NR) firstInt(NR_ARFCN, body) else firstInt(EARFCN, body)
            val rsrp = if (rat == Rat.NR) sane(firstInt(NR_RSRP, body)) else sane(firstInt(LTE_RSRP, body))
            val rsrq = if (rat == Rat.NR) sane(firstInt(NR_RSRQ, body)) else sane(firstInt(LTE_RSRQ, body))
            val sinr = if (rat == Rat.NR) sane(firstInt(NR_SINR, body)) else sane(firstInt(LTE_SINR, body))
            // 没有任何编号的小区（如 Cdma 占位）跳过
            if (ci != null && ci > 0L) {
                out.add(
                    RegCell(
                        rat = rat,
                        ci = ci,
                        pci = firstInt(PCI, body),
                        arfcn = arfcn,
                        mcc = firstString(MCC, body),
                        mnc = firstString(MNC, body),
                        tac = firstLong(TAC, body),
                        registered = registered,
                        rsrp = rsrp,
                        rsrq = rsrq,
                        sinr = sinr,
                    ),
                )
            }
            from = end + 1
        }
        return out
    }

    /** 从 open 位置的 '{' 起找配平 '}' 的下标；找不到返回 -1 */
    private fun balancedEnd(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun firstInt(re: Regex, body: String): Int? = re.find(body)?.groupValues?.get(1)?.toIntOrNull()

    private fun firstLong(re: Regex, body: String): Long? = re.find(body)?.groupValues?.get(1)?.toLongOrNull()

    private fun firstString(re: Regex, body: String): String? = re.find(body)?.groupValues?.get(1)?.takeIf { it != "null" }

    /** Android 用 Int.MAX_VALUE / Int.MIN_VALUE 表示「无测量值」，落到数据层要当缺失 */
    private fun sane(v: Int?): Int? = v?.takeIf { it != Int.MAX_VALUE && it != Int.MIN_VALUE && it != 2147483647 }

    /**
     * 把注册态与邻区整理成 App 的 (CellIdentity, SignalData) 列表。
     * 邻区去重：同一 (rat, ci) 只保留第一条；按 RSRP 从强到弱排序，UI 展示更直观。
     */
    fun toPairs(cells: List<RegCell>): List<Pair<CellIdentity, SignalData?>> {
        val seen = HashSet<Pair<Rat, Long?>>()
        return cells
            .filter { it.ci != null && it.ci > 0L }
            .sortedByDescending { it.rsrp ?: Int.MIN_VALUE }
            .filter { seen.add(it.rat to it.ci) }
            .map { it.identity() to it.signal() }
    }
}
