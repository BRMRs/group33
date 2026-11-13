package com.group33.medicationreminder.caregiver.plan

/** Keep literal candidates: never infer a dose, schedule, or medicine identity. */
internal object OcrCandidates {
    fun lines(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotBlank() && it.length <= 200 }
        .distinct().take(40).toList()
    // Prefer an explicit name label; otherwise a single recognizable dosage-form name.
    // Ambiguous packaging text must not be silently promoted to a drug name.
    fun name(text: String): String? {
        val lines = lines(text)
        val label = Regex("^(?:通用名称|药品名称|商品名称|Medicine name|Drug name)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val labelled = lines.mapNotNull { label.matchEntire(it)?.groupValues?.get(1)?.trim() }.distinct()
        if (labelled.size == 1) return labelled.single()
        if (labelled.isNotEmpty()) return null
        val form = Regex("^[\\p{L} ·-]{2,60}(?:片|胶囊|颗粒|口服液|滴眼液|软膏| tablets| capsules)$", RegexOption.IGNORE_CASE)
        return lines.filter { form.matches(it) }.distinct().singleOrNull()
    }
}
