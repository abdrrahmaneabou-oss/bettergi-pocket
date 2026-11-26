package com.bettergi.pocket.genshin

object GenshinPackages {
    val CANDIDATES: List<String> = listOf(
        "com.miHoYo.Yuanshen",
        "com.miHoYo.ys.bilibili",
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.cloudgames.ys",
        "com.miHoYo.cloudgames.GenshinImpact",
        "com.miHoYo.ys.mi",
    )

    fun isGenshinPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (CANDIDATES.any { it.equals(packageName, ignoreCase = true) }) return true
        val lower = packageName.lowercase()
        return lower.contains("yuanshen") ||
            lower.contains("genshinimpact") ||
            lower.startsWith("com.mihoyo.ys.") ||
            lower.startsWith("com.mihoyo.cloudgames.ys")
    }

    fun pickPreferred(installed: Collection<String>): String? {
        val set = installed.toSet()
        CANDIDATES.firstOrNull { it in set }?.let { return it }
        return installed.firstOrNull { isGenshinPackage(it) }
    }

    fun displayName(packageName: String): String = when (packageName) {
        "com.miHoYo.Yuanshen" -> "الخادم الصيني الرسمي"
        "com.miHoYo.ys.bilibili" -> "خادم Bilibili"
        "com.miHoYo.GenshinImpact" -> "الخادم الدولي"
        "com.miHoYo.cloudgames.ys" -> "جينشن السحابية"
        "com.miHoYo.cloudgames.GenshinImpact" -> "جينشن السحابية الدولية"
        "com.miHoYo.ys.mi" -> "خادم شاومي"
        else -> "جينشن"
    }

    fun shouldAttemptAutoLaunch(
        enabled: Boolean,
        genshinInForeground: Boolean?,
        alreadyAttempted: Boolean,
        allowed: Boolean,
    ): Boolean {
        if (!enabled || !allowed || alreadyAttempted) return false
        return genshinInForeground != true
    }
}
