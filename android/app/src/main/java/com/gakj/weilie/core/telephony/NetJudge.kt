package com.gakj.weilie.core.telephony

/**
 * 数据卡当前的组网形态。
 *
 * 真机实测（移动 SA + 联通 LTE 双卡）：只看「扫到 NR」或 `dataNetworkType == NR`
 * 会把 LTE 卡误判成 NSA 并渲染 LTE 锚点；必须按「注册小区 + 数据卡」再分 SA / NSA。
 */
enum class NetMode(val label: String) {
    /** 5G 独立组网：只有 NR 注册小区，不存在 LTE 锚点 */
    SA("5G SA"),

    /** 5G 非独立组网：LTE 锚点（注册）+ NR 辅载波 */
    NSA("5G NSA"),

    /** 纯 4G */
    LTE("4G LTE"),

    /** 2G/3G 或无法识别制式 */
    LEGACY("2G/3G"),

    UNKNOWN("未知");

    val is5g: Boolean get() = this == SA || this == NSA
}

/**
 * 不依赖 Android 框架的判定逻辑（可在 JVM 单元测试里直接验证）：
 * - SA / NSA / LTE / LEGACY 组网形态
 * - 运营商键（用于把「别家小区」从这张卡的列表里剔除）
 */
object NetJudge {

    /**
     * 判定组网形态。
     *
     * 规则（真机校准）：
     * - NR 注册 + LTE 注册 → NSA（LTE 是锚点）
     * - 只有 NR 注册 → SA（没有锚点，UI 必须隐藏「锚点 LTE」）
     * - 只有 LTE 注册但扫到 NR 小区（或数据网是 NR）→ NSA
     * - 只有 LTE 注册 → LTE；只有 2G/3G 注册 → LEGACY；都没有 → UNKNOWN
     */
    fun classify(
        registeredNr: Boolean,
        registeredLte: Boolean,
        registeredAny: Boolean,
        nrNeighborCount: Int,
        dataRatIsNr: Boolean,
    ): NetMode = when {
        registeredNr && registeredLte -> NetMode.NSA
        registeredNr -> NetMode.SA
        registeredLte && (nrNeighborCount > 0 || dataRatIsNr) -> NetMode.NSA
        registeredLte -> NetMode.LTE
        registeredAny -> NetMode.LEGACY
        else -> NetMode.UNKNOWN
    }

    /** 运营商名 → 内部键；认不出返回 null（漫游 / 虚拟运营商不做过滤）。 */
    fun carrierKey(carrier: String): String? = when {
        carrier.contains("移动") -> "CMCC"
        carrier.contains("联通") -> "CUCC"
        carrier.contains("电信") -> "CTCC"
        carrier.contains("广电") -> "CBN"
        carrier.contains("铁路") -> "RAIL"
        else -> null
    }

    /**
     * 中国 PLMN → 运营商键。
     *
     * 运营商自用 MNC（如移动 460-07）与小区广播的 MNC（常见 460-00）要归到同一家，
     * 否则双卡时会把自家小区当别家剔除。
     */
    fun operatorKey(mcc: String?, mnc: String?): String? {
        if (mcc != "460" || mnc.isNullOrBlank()) return null
        return when (mnc.trim().padStart(2, '0')) {
            "00", "02", "04", "07", "08" -> "CMCC"
            "01", "06", "09" -> "CUCC"
            "03", "05", "11" -> "CTCC"
            "15" -> "CBN"
            "20" -> "RAIL"
            else -> null
        }
    }

    /** 这条小区属于这张卡吗？认不出运营商的一律保留。 */
    fun belongsTo(plmnMcc: String?, plmnMnc: String?, carrier: String): Boolean {
        val expected = carrierKey(carrier) ?: return true
        val actual = operatorKey(plmnMcc, plmnMnc) ?: return true
        return actual == expected
    }
}
