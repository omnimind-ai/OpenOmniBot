package cn.com.omnimind.bot.runlog

internal object RunLogPagePackageInference {
    fun effectivePackage(recordedPackage: String, xml: String): String {
        val recorded = recordedPackage.trim()
        val inferred = dominantPackage(xml)
        if (inferred.isEmpty()) return recorded
        if (recorded.isEmpty() || recorded == "android") return inferred
        if (recorded == inferred) return recorded
        if (recorded !in packageCounts(xml)) return inferred
        return recorded
    }

    private fun dominantPackage(xml: String): String {
        if (xml.isBlank()) return ""
        val counts = packageCounts(xml)
        return counts.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length }
        )?.key.orEmpty()
    }

    private fun packageCounts(xml: String): Map<String, Int> {
        if (xml.isBlank()) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        fun add(raw: String?) {
            val name = raw?.trim().orEmpty()
            if (!isPackageLike(name)) return
            counts[name] = (counts[name] ?: 0) + 1
        }
        PACKAGE_ATTR.findAll(xml).forEach { add(it.groupValues[1]) }
        RESOURCE_ID_ATTR.findAll(xml).forEach { add(it.groupValues[1]) }
        return counts
    }

    private fun isPackageLike(value: String): Boolean {
        if (value == "android") return false
        if (!value.contains('.')) return false
        return PACKAGE_NAME.matches(value)
    }

    private val PACKAGE_ATTR = Regex("""\bpackage\s*=\s*["']([^"']+)["']""")
    private val RESOURCE_ID_ATTR = Regex("""\bresource-id\s*=\s*["']([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+):id/[^"']*["']""")
    private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""")
}
