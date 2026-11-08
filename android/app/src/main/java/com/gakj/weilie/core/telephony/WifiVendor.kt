package com.gakj.weilie.core.telephony

/**
 * BSSID → 厂商名的离线查表（按 OUI，即 MAC 前 3 字节）。
 *
 * 说明：
 * - 扫描结果里的 BSSID 是 AP 的真实 MAC，因此可以直接用 OUI 判断厂商；
 * - 只收录常见网络/终端厂商的一个离线子集（避免 APK 里塞几 MB 的 IEEE 全表）；
 * - 本地管理地址（MAC 第二字节的 U/L 位为 1，如虚拟机/随机 MAC）不查表，返回 null；
 * - 查不到返回 null，UI 显示「—」。
 */
object WifiVendor {

    private val OUI: Map<String, String> = mapOf(
        // 华为
        "00E0FC" to "华为",
        "24DBAC" to "华为",
        "4846FB" to "华为",
        // 小米
        "640980" to "小米",
        "7811DC" to "小米",
        "8CBEBE" to "小米",
        // TP-LINK
        "50C7BF" to "TP-LINK",
        "A42BB0" to "TP-LINK",
        "C4E984" to "TP-LINK",
        // 新华三
        "002389" to "新华三",
        // D-Link
        "1C7EE5" to "D-Link",
        // 网件
        "00146C" to "网件",
        "C03F0E" to "网件",
        // 腾达
        "C83A35" to "腾达",
        // 思科
        "00000C" to "思科",
        // Aruba
        "000B86" to "Aruba",
        // Ubiquiti
        "24A43C" to "Ubiquiti",
        "74ACB9" to "Ubiquiti",
        "F09FC2" to "Ubiquiti",
        // 苹果
        "F01898" to "苹果",
        "A4C361" to "苹果",
        "3C0754" to "苹果",
        // 三星
        "8C7712" to "三星",
        // 英特尔
        "001B21" to "英特尔",
        "3413E8" to "英特尔",
        // 瑞昱
        "00E04C" to "瑞昱",
        // 联发科
        "000CE7" to "联发科",
        // 乐鑫（ESP32/ESP8266）
        "240AC4" to "乐鑫",
        "30AEA4" to "乐鑫",
        "84CCA8" to "乐鑫",
        "A4CF12" to "乐鑫",
        // 谷歌
        "001A11" to "谷歌",
        // 亚马逊
        "44650D" to "亚马逊",
        // 惠普
        "3CD92B" to "惠普",
        // 戴尔
        "001422" to "戴尔",
        // 微软（Hyper-V）
        "00155D" to "微软",
        // VMware
        "000C29" to "VMware",
        "005056" to "VMware",
        // VirtualBox / QEMU
        "080027" to "VirtualBox",
        "525400" to "QEMU",
        // 树莓派
        "B827EB" to "树莓派",
        "DCA632" to "树莓派",
        "E45F01" to "树莓派",
    )

    /** 查 OUI 厂商名；格式非法、随机/虚拟 MAC 或未收录返回 null。 */
    fun lookup(bssid: String?): String? {
        val hex = bssid
            ?.trim()
            ?.uppercase()
            ?.replace("-", ":")
            ?.split(":")
            ?.take(3)
            ?.joinToString("")
            ?: return null
        if (hex.length != 6 || hex.any { Character.digit(it, 16) < 0 }) return null
        // U/L 位（第二字节最低位）为 1：本地管理地址，不按 OUI 判厂商
        val localAdmin = Character.digit(hex[1], 16) and 0x2 != 0
        if (localAdmin) return null
        return OUI[hex]
    }
}
