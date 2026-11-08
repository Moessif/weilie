package com.gakj.weilie.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.gakj.weilie.core.model.CellIdentity
import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SignalData
import com.gakj.weilie.core.model.WifiAp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 邻区及其信号测量值（[CellIdentity] 本身不携带 RSRP，专家面板展示邻区强度用）。 */
data class NeighborCell(
    val identity: CellIdentity,
    val signal: SignalData? = null,
)

/** 一次完整的小区快照 */
data class CellSnapshot(
    val tsEpochMs: Long = System.currentTimeMillis(),
    val serving: CellIdentity? = null,
    val signal: SignalData? = null,
    val neighbors: List<CellIdentity> = emptyList(),
    val wifi: List<WifiAp> = emptyList(),
    val deviceModel: String = Build.MODEL ?: "",
    /**
     * 网络锚点制式，用于判断"其实是 5G"：
     * - NR 服务小区 → [Rat.NR]（SA 5G）
     * - LTE 服务小区且检出未注册 NR 邻区 → [Rat.LTE]（NSA：4G 锚点 + 5G 辅载波，编号仍是 LTE 的 ECI）
     * - 其它 → null
     */
    val anchorRat: Rat? = null,
    /** 检出但未注册的 NR 小区数量；仅看邻居列表，不额外抓取 */
    val nrNeighborCount: Int = 0,
    /**
     * 每张 SIM 卡各自驻留的小区（一行一卡，NR 优先；NSA 时同一张卡可能有两行：NR 行在前、LTE 锚点在后）。
     * 读不到订阅列表（没有 READ_PHONE_STATE / 老 ROM）时退化为一条 slot=0 的记录，保证 UI 不空。
     */
    val sims: List<SimCell> = emptyList(),
    /**
     * 当前「数据网络类型」映射（`tm.dataNetworkType`，读不到/无权限为 null）。
     * 用途：ROM（HyperOS/Android 17）可能把未注册 NR 小区从 allCellInfo 过滤掉，
     * 此时 CellInfo 只剩 LTE，但数据网实际是 NR(NSA)，用它兜底标注。
     */
    val dataRat: Rat? = null,
    /** [dataRat] 的展示标签："5G"、"4G"、"3G"、"2G"，未知为空串。 */
    val dataRatLabel: String = "",
    /** 与 [neighbors] 顺序一致的邻区测量值（[CellIdentity] 不携带信号，专家面板展示邻区强度用）。 */
    val neighborCells: List<NeighborCell> = emptyList(),
)

/** 免 root 采集通道抽象（华为 AIDL 通道未来实现同一接口插入） */
interface CellSnapshotSource {
    val snapshot: StateFlow<CellSnapshot?>
    fun start()
    fun stop()
}

/**
 * Telephony 免 root 采集实现。
 * - API ≥31：registerTelephonyCallback（CellInfo + SignalStrength）
 * - API 26–30：PhoneStateListener（LISTEN_CELL_INFO | LISTEN_SIGNAL_STRENGTHS）
 * - 同时 1s 轮询 allCellInfo 兜底（部分 ROM 监听不回调）
 * - 邻区 = 未注册小区；部分机型不返回邻区（W1 摸底重点）
 */
class AndroidCellReader(private val context: Context) : CellSnapshotSource {

    private val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wm: WifiManager? =
        context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** 读订阅列表要 READ_PHONE_STATE；拿不到时按默认订阅回退（见 readSims） */
    private val sm: SubscriptionManager? =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    private var simsCache: List<SimCell> = emptyList()
    private var simsCachedAt = 0L

    private val _snapshot = MutableStateFlow<CellSnapshot?>(null)
    override val snapshot: StateFlow<CellSnapshot?> = _snapshot

    private var callback31: Any? = null
    private var listener: PhoneStateListener? = null
    private var listening = false

    private val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val hasPhoneStatePermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (listening) return
        listening = true
        if (!hasLocationPermission) {
            // 无定位权限时 getAllCellInfo 会抛 SecurityException，快照保持空，UI 引导授权
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener, TelephonyCallback.SignalStrengthsListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = consume(cellInfo, null)
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = consume(tm.allCellInfo ?: emptyList(), signalStrength)
            }
            callback31 = cb
            try {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } catch (_: SecurityException) {
                // 权限授予与 telephony registry 同步存在竞态（真机/模拟器均观察到）：
                // 注册失败不致命——SamplingController 的 1s pollNow() 轮询兜底继续供数
                callback31 = null
            } catch (_: IllegalStateException) {
                callback31 = null
            }
        } else {
            val l = object : PhoneStateListener() {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = consume(cellInfo, null)
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = consume(tm.allCellInfo ?: emptyList(), signalStrength)
            }
            listener = l
            try {
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            } catch (_: SecurityException) {
                listener = null
            }
        }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        try {
            if (Build.VERSION.SDK_INT >= 31 && callback31 != null) {
                @Suppress("UNCHECKED_CAST")
                tm.unregisterTelephonyCallback(callback31 as TelephonyCallback)
            } else if (listener != null) {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
        callback31 = null; listener = null
    }

    /** 供轮询兜底调用 */
    fun pollNow() {
        if (!hasLocationPermission) return
        consume(tm.allCellInfo ?: emptyList(), null)
    }

    @Synchronized
    private fun consume(cellInfos: List<CellInfo>, ss: SignalStrength?) {
        val now = System.currentTimeMillis()
        var serving: CellIdentity? = null
        var signal: SignalData? = null
        val neighbors = ArrayList<CellIdentity>()
        val neighborCells = ArrayList<NeighborCell>()
        val parsed = ArrayList<Pair<CellIdentity, SignalData?>>(cellInfos.size)
        for (ci in cellInfos) {
            try {
                val pair = parseCellInfo(ci, ss)
                parsed.add(pair)
                val (id, sig) = pair
                if (id.isRegistered) {
                    serving = id
                    if (sig != null) signal = sig
                } else {
                    neighbors.add(id)
                    neighborCells.add(NeighborCell(id, sig))
                }
            } catch (_: Exception) {
                // 单个小区解析失败不影响整体
            }
        }
        val wifi = readWifi()
        // NSA 判断：NR 邻区在 NSA 下是未注册的 5G 辅载波；LTE 服务小区 + NR 邻区 > 0 即疑似 NSA 5G
        val nrNeighborCount = neighbors.count { it.rat == Rat.NR }
        val anchorRat = when {
            serving?.rat == Rat.NR -> Rat.NR
            serving?.rat == Rat.LTE && nrNeighborCount > 0 -> Rat.LTE
            else -> null
        }
        // ROM 可能过滤掉未注册 NR 小区；用数据网络类型兜底判断「实际是 5G NSA」
        val dataRat = readDataRat()
        val sims = readSims(cellInfos, parsed, now, dataRat)
        _snapshot.value = CellSnapshot(
            tsEpochMs = now,
            serving = serving,
            signal = signal,
            neighbors = neighbors,
            wifi = wifi,
            anchorRat = anchorRat,
            nrNeighborCount = nrNeighborCount,
            sims = sims,
            dataRat = dataRat,
            dataRatLabel = dataRatLabelOf(dataRat),
            neighborCells = neighborCells,
        )
    }

    /**
     * 按「一行一张 SIM 卡」读取各卡驻留小区。
     *
     * 取值路径：`SubscriptionManager.getActiveSubscriptionInfoList()`（需 READ_PHONE_STATE）
     * → 每张卡 `createForSubscriptionId(subId).allCellInfo`（需定位权限）
     * → 抽成 [NrDesc]/[LteDesc] → [planSimCells] 纯函数挑选（NR 优先、NSA 补一行）。
     * 任一步没权限/ROM 不返回，都回退到默认订阅的 registered 小区（slot=0），保证 UI 不空。
     * 结果缓存 1s：CellInfo 回调可能一秒多次，避免每秒都对每张卡重复 IPC。
     */
    private fun readSims(
        cellInfos: List<CellInfo>,
        parsed: List<Pair<CellIdentity, SignalData?>>,
        now: Long,
        dataRat: Rat?,
    ): List<SimCell> {
        if (now - simsCachedAt < SIMS_REFRESH_MS && simsCache.isNotEmpty()) return simsCache
        // dataNetworkType 只描述默认数据卡；拿不到默认数据卡 id 时退化为对所有卡生效
        val defaultDataSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: SecurityException) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val inputs = ArrayList<SimInput>(2)
        if (hasPhoneStatePermission) {
            val subs: List<SubscriptionInfo> = try {
                sm?.activeSubscriptionInfoList.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            for (sub in subs) {
                try {
                    val subTm: TelephonyManager = try {
                        tm.createForSubscriptionId(sub.subscriptionId)
                    } catch (_: Exception) {
                        tm
                    }
                    val cells: List<CellInfo> = try {
                        subTm.allCellInfo.orEmpty()
                    } catch (_: SecurityException) {
                        emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (cells.isEmpty()) continue
                    // 运营商名优先级：卡自带 carrierName → displayName → 网络名 → 卡槽号
                    val carrier = sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: sub.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: subTm.networkOperatorName?.takeIf { it.isNotBlank() }
                        ?: "SIM ${sub.simSlotIndex + 1}"
                    inputs += SimInput(
                        slot = sub.simSlotIndex.coerceAtLeast(0),
                        carrier = carrier,
                        mccMnc = subTm.networkOperator.orEmpty(),
                        nrCells = if (Build.VERSION.SDK_INT >= 30) {
                            cells.filterIsInstance<CellInfoNr>().mapNotNull { nrDescOf(it) }
                        } else {
                            emptyList()
                        },
                        lteCells = cells.filterIsInstance<CellInfoLte>().mapNotNull { lteDescOf(it) },
                        dataRat = dataRat,
                        isDataSub = defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
                            sub.subscriptionId == defaultDataSubId,
                    )
                } catch (_: Exception) {
                    // 单张卡失败不影响其它卡
                }
            }
        }
        val perSub = planAllSims(inputs)
        val sims = perSub.ifEmpty { fallbackSims(cellInfos, parsed, dataRat, defaultDataSubId) }
        simsCache = sims
        simsCachedAt = now
        return sims
    }

    /** CellInfoNr → 纯描述。API < 30 没有可用的 NR identity，返回 null。 */
    private fun nrDescOf(ci: CellInfoNr): NrDesc? {
        if (Build.VERSION.SDK_INT < 30) return null
        val id = ci.cellIdentity as? CellIdentityNr ?: return null
        val cs = ci.cellSignalStrength as? CellSignalStrengthNr
        return NrDesc(
            pci = id.pci,
            arfcn = id.nrarfcn,
            nci = id.nci,
            ssRsrp = cs?.ssRsrp ?: SimCell.UNKNOWN,
            ssSinr = cs?.ssSinr ?: SimCell.UNKNOWN,
            mcc = id.mccString,
            mnc = id.mncString,
            isRegistered = ci.isRegistered,
        )
    }

    /** CellInfoLte → 纯描述。 */
    private fun lteDescOf(ci: CellInfoLte): LteDesc {
        val id = ci.cellIdentity
        val cs = ci.cellSignalStrength
        return LteDesc(
            pci = id.pci.takeIf { it != Int.MAX_VALUE } ?: -1,
            earfcn = id.earfcn.takeIf { it != Int.MAX_VALUE } ?: -1,
            eci = id.ci.takeIf { it != Int.MAX_VALUE && it != 0 }
                ?.let { it.toLong() and 0xFFFFFFFL } ?: -1L,
            rsrp = cs.rsrpCompat() ?: SimCell.UNKNOWN,
            sinr = cs.sinrCompat() ?: SimCell.UNKNOWN,
            mcc = id.mccString,
            mnc = id.mncString,
            isRegistered = ci.isRegistered,
        )
    }

    /**
     * 读不到订阅列表（无 READ_PHONE_STATE / 老 ROM）时的兜底：只产出一行，默认数据卡优先。
     *
     * 真机双卡时副卡常常拿不到自己的小区，若给每张卡都塞同一份数据会显示两行完全相同的小区；
     * 这里宁可只显示默认数据卡（拿不到默认卡就 slot 0）一行，另一张卡不显示行。
     */
    private fun fallbackSims(
        cellInfos: List<CellInfo>,
        parsed: List<Pair<CellIdentity, SignalData?>>,
        dataRat: Rat?,
        defaultDataSubId: Int,
    ): List<SimCell> {
        val subs: List<SubscriptionInfo> = try {
            sm?.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val primarySub = subs.firstOrNull { it.subscriptionId == defaultDataSubId }
            ?: subs.minByOrNull { it.simSlotIndex }
        val slot = primarySub?.simSlotIndex?.coerceAtLeast(0) ?: 0
        val carrier = primarySub?.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: primarySub?.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
            ?: "SIM ${slot + 1}"
        val mccMnc = primarySub?.let { sub ->
            runCatching { tm.createForSubscriptionId(sub.subscriptionId).networkOperator }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: tm.networkOperator.orEmpty()
        val nrCells = if (Build.VERSION.SDK_INT >= 30) {
            cellInfos.filterIsInstance<CellInfoNr>().mapNotNull { nrDescOf(it) }
        } else {
            emptyList()
        }
        val lteCells = cellInfos.filterIsInstance<CellInfoLte>().mapNotNull { lteDescOf(it) }
        if (nrCells.isNotEmpty() || lteCells.isNotEmpty()) {
            val planned = planSimCells(SimInput(slot, carrier, mccMnc, nrCells, lteCells, dataRat = dataRat))
            if (planned.isNotEmpty()) return planned
        }
        // 连 LTE/NR 都没有（2G/3G 或 ROM 全屏蔽）：用 registered 服务小区给一条，避免 UI 空白
        val registered = parsed.firstOrNull { it.first.isRegistered } ?: return emptyList()
        val (id, sig) = registered
        return listOf(
            SimCell(
                slot = slot,
                carrier = carrier,
                rat = if (id.rat == Rat.LTE && dataRat == Rat.NR) "NR(NSA)" else id.rat.name,
                band = "-",
                arfcn = id.arfcn ?: -1,
                pci = id.pci ?: -1,
                cellId = id.ci ?: -1L,
                rsrp = sig?.rsrp ?: SimCell.UNKNOWN,
                sinr = sig?.sinr ?: SimCell.UNKNOWN,
                mccMnc = mccMnc,
            )
        )
    }

    /**
     * 当前数据网络类型（不是小区制式）。ROM（HyperOS/Android 17）可能把未注册的 NR 小区
     * 从 allCellInfo 里过滤掉，导致 UI 只能从 registered 小区推制式；这里用
     * `tm.dataNetworkType` 兜底判断「实际是 5G NSA」，读不到返回 null（保持旧行为）。
     */
    private fun readDataRat(): Rat? {
        networkTypeOrNull { tm.dataNetworkType }?.let { ratOfNetworkType(it) }?.let { return it }
        // 语音网络类型仅作补充（个别 ROM 数据网络类型不可用但语音可用）
        return networkTypeOrNull { tm.voiceNetworkType }?.let { ratOfNetworkType(it) }
    }

    /** 读网络类型并做异常保护：HyperOS 上可能抛 SecurityException（权限竞态）/其它 ROM 限制 → null。 */
    private fun networkTypeOrNull(read: () -> Int): Int? = try {
        read().takeIf { it > 0 }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    /** TelephonyManager.NETWORK_TYPE_* → [Rat]；未知/无映射返回 null。 */
    private fun ratOfNetworkType(type: Int): Rat? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> Rat.NR
        TelephonyManager.NETWORK_TYPE_LTE -> Rat.LTE
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> Rat.TDSCDMA
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP -> Rat.WCDMA
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> Rat.GSM
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> Rat.CDMA
        else -> null
    }

    /** [CellSnapshot.dataRat] → 给 UI 的展示标签。 */
    private fun dataRatLabelOf(rat: Rat?): String = when (rat) {
        Rat.NR -> "5G"
        Rat.LTE -> "4G"
        Rat.WCDMA, Rat.TDSCDMA -> "3G"
        Rat.GSM, Rat.CDMA -> "2G"
        else -> ""
    }

    private fun readWifi(): List<WifiAp> {
        if (!hasLocationPermission) return emptyList()
        return try {
            wm?.scanResults
                ?.sortedByDescending { it.level }
                ?.take(8)
                ?.map {
                    WifiAp(
                        ssid = it.SSID,
                        bssid = it.BSSID,
                        rssi = it.level,
                        channel = it.frequency?.let { f -> frequencyToChannel(f) },
                        vendor = WifiVendor.lookup(it.BSSID),
                    )
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun frequencyToChannel(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2407) / 5
        freq in 5170..5825 -> (freq - 5000) / 5
        freq >= 5955 -> (freq - 5950) / 5
        else -> 0
    }

    private fun parseCellInfo(ci: CellInfo, ss: SignalStrength?): Pair<CellIdentity, SignalData?> {
        return when (ci) {
            is CellInfoLte -> {
                val id: CellIdentityLte = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.LTE,
                    mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.tac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.ci.takeIf { it != Int.MAX_VALUE && it != 0 }?.let { it.toLong() and 0xFFFFFFFL },
                    pci = id.pci.takeIf { it != Int.MAX_VALUE },
                    arfcn = id.earfcn.takeIf { it != Int.MAX_VALUE },
                    band = if (Build.VERSION.SDK_INT >= 28) id.bands.firstOrNull() else null,
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(
                    rsrp = cs.rsrpCompat(), rsrq = cs.rsrqCompat(), sinr = cs.sinrCompat(), rssi = cs.rssiCompat(), dbm = cs.dbm,
                )
            }
            is CellInfoGsm -> {
                val id: CellIdentityGsm = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.GSM, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    arfcn = id.arfcn.takeIf { it != Int.MAX_VALUE },
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(rssi = cs.dbm.takeIf { it in -120..0 }, dbm = cs.dbm)
            }
            is CellInfoWcdma -> {
                val id: CellIdentityWcdma = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.WCDMA, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.let { it.toLong() and 0xFFFFL },
                    arfcn = id.uarfcn.takeIf { it != Int.MAX_VALUE },
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(dbm = cs.dbm, rssi = cs.dbm.takeIf { it in -120..0 })
            }
            is CellInfoTdscdma -> {
                val id: CellIdentityTdscdma = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.TDSCDMA, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    isRegistered = ci.isRegistered,
                )
                cid to SignalData(dbm = ci.cellSignalStrength.dbm)
            }
            is CellInfoCdma -> {
                val id: CellIdentityCdma = ci.cellIdentity
                val cs: CellSignalStrengthCdma = ci.cellSignalStrength
                val cid = CellIdentity(
                    rat = Rat.CDMA,
                    ci = id.basestationId.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    isRegistered = ci.isRegistered,
                )
                cid to SignalData(dbm = cs.dbm)
            }
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    val id = ci.cellIdentity as? CellIdentityNr
                    val cs = ci.cellSignalStrength as? CellSignalStrengthNr
                    if (id == null) return Pair(CellIdentity(isRegistered = ci.isRegistered), null)
                    val cid = CellIdentity(
                        rat = Rat.NR, mcc = id.mccString, mnc = id.mncString,
                        lacOrTac = id.tac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                        ci = id.nci.takeIf { it > 0 },
                        pci = id.pci.takeIf { it != Int.MAX_VALUE },
                        arfcn = id.nrarfcn.takeIf { it != Int.MAX_VALUE },
                        isRegistered = ci.isRegistered,
                    )
                    cid to SignalData(
                        rsrp = cs?.ssRsrp?.takeIf { it != Int.MAX_VALUE },
                        rsrq = cs?.ssRsrq?.takeIf { it != Int.MAX_VALUE },
                        sinr = cs?.ssSinr?.takeIf { it != Int.MAX_VALUE },
                        dbm = cs?.dbm,
                    )
                } else null
            }
            else -> null
        } ?: Pair(CellIdentity(isRegistered = ci.isRegistered), null)
    }

    // CellSignalStrengthLte 各字段在不同 API 级别的取值包装（MAX_VALUE → null）
    private fun CellSignalStrengthLte.rsrpCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rsrp.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.rsrqCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rsrq.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.rssiCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rssi.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.sinrCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rssnr.takeIf { it != Int.MAX_VALUE }?.let { (it / 10.0).toInt() } else null

    private companion object {
        /** 各卡小区/订阅表刷新间隔：避免 CellInfo 回调一秒多次时对每张卡重复 IPC */
        const val SIMS_REFRESH_MS = 1_000L
    }
}
