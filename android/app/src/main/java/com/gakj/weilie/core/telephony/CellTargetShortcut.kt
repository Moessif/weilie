package com.gakj.weilie.core.telephony

/**
 * 「用本机当前基站」的格式化规则。
 *
 * 抽出来单独放是为了可单测：页面里只负责取到 [SimCell]，怎么变成一个
 * 上级参数解析器认识的字符串、怎么写提示，都由这里决定。
 *
 * 口径：
 * - 5G（NR）：`NCI <原始 36bit 值>` —— 解析器见到 NCI 前缀按 5G 处理；
 * - 4G（LTE）：`ECI <原始 28bit 值>`；
 * - 2G/3G（GSM/WCDMA/CDMA）：返回 null —— 本 App 的覆盖拟合与目标判定只对 4G/5G 有意义，
 *   而且 SimCell 里没有 LAC，拼不出合规的「LAC+CI」，宁可不填也不填错；
 * - `cellId <= 0`（读不到小区）：返回 null。
 */
object CellTargetShortcut {

    /** 拼成可直接粘进目标输入框的文本；不支持的制式返回 null */
    fun rawOf(sim: SimCell): String? = when {
        sim.cellId <= 0L -> null
        sim.rat.equals("NR", true) -> "NCI ${sim.cellId}"
        sim.rat.equals("LTE", true) -> "ECI ${sim.cellId}"
        else -> null
    }

    /** 给用户看的一句「已填入：中国移动 · NR N41 · 1386046-4」 */
    fun hintOf(sim: SimCell): String = listOf(sim.carrier, sim.ratBand, sim.cellIdText)
        .filter { it.isNotBlank() && it != "-" }
        .joinToString(" · ")

    /**
     * 从一组 SIM 行里挑「要设成目标」的那一行。
     *
     * [servingCi] 是采集快照里「数据卡当前注册小区」的编号：用户说的是「当前手机卡连接的基站」，
     * 双卡时应该跟数据卡走，而不是随便挑一张卡。命中则直接用那一行；否则按
     * NR 优先（SA 服务小区 / NSA 的 NR 辅载波）→ LTE（含 NSA 锚点）兜底。
     */
    fun pick(sims: List<SimCell>, servingCi: Long? = null): SimCell? {
        if (servingCi != null && servingCi > 0L) {
            sims.firstOrNull { it.cellId == servingCi }?.let { return it }
        }
        return sims.firstOrNull { it.rat.equals("NR", true) && it.cellId > 0L }
            ?: sims.firstOrNull { it.rat.equals("LTE", true) && it.cellId > 0L }
    }
}
