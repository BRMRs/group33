package com.group33.medicationreminder.caregiver.plan

import android.content.Context
import android.graphics.Bitmap
import android.widget.TextView
import com.group33.medicationreminder.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** A status line only. Recognition begins with explicit photo confirmation in the host. */
internal class PhotoTextSuggestions(context: Context, private val applyName: (String) -> Boolean) : TextView(context) {
    private var photo: Bitmap? = null
    private var epoch = 0
    private var editable = false
    private var running = false
    init { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE; visibility = GONE }
    fun bind(bitmap: Bitmap?) { clear(); photo = bitmap }
    fun setEditable(value: Boolean) { editable = value; if (!value) cancel() }
    fun cancel() { epoch++; running = false; text = ""; visibility = GONE }
    fun clear() { cancel(); photo = null; editable = false }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    fun recognize() {
        val bitmap = photo ?: return
        if (!editable || running || bitmap.isRecycled) return
        val token = ++epoch
        running = true; visibility = VISIBLE; setText(R.string.ocr_loading)
        val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
        if (owned == null) { failed(token); return }
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            catch (_: Exception) { owned.recycle(); failed(token); return }
        try {
            recognizer.process(InputImage.fromBitmap(owned, 0))
                .addOnSuccessListener { result ->
                    if (token != epoch || !editable || !isAttachedToWindow) return@addOnSuccessListener
                    val candidate = OcrCandidates.name(result.text)
                    if (candidate == null) setText(R.string.ocr_empty)
                    else if (applyName(candidate)) setText(R.string.ocr_filled)
                    else { text = ""; visibility = GONE }
                }
                .addOnFailureListener { failed(token) }
                .addOnCompleteListener {
                    recognizer.close(); owned.recycle()
                    if (token == epoch) running = false
                }
        } catch (_: Exception) { recognizer.close(); owned.recycle(); failed(token) }
    }
    private fun failed(token: Int) {
        if (token != epoch) return
        running = false; setText(R.string.ocr_failed)
    }
}
