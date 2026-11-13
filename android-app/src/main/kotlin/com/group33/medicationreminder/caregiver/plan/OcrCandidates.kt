package com.group33.medicationreminder.caregiver.plan

/** Keep literal candidates: never infer a dose, schedule, or medicine identity. */
internal object OcrCandidates {
    fun lines(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotBlank() && it.length <= 200 }
        .distinct().take(40).toList()
}
