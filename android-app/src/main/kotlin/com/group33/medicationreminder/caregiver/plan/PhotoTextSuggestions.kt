package com.group33.medicationreminder.caregiver.plan

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.*
import com.group33.medicationreminder.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Local, untrusted text only. The host must clear on authorization/context loss. */
internal class PhotoTextSuggestions(context: Context, private val applyName: (String) -> Unit, private val applyNotes: (String) -> Unit) : LinearLayout(context) {
    private val scan = Button(context).apply { setText(R.string.ocr_scan) }
    private val message = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val choices = LinearLayout(context).apply { orientation = VERTICAL }
    private var photo: Bitmap? = null
    private var epoch = 0
    private var editable = false
    private var running = false
    init {
        orientation = VERTICAL
        addView(scan); addView(message); addView(choices)
        scan.setOnClickListener { recognize() }
        clear()
    }
    fun bind(bitmap: Bitmap?) { clear(); photo = bitmap; visibility = if (bitmap == null) GONE else VISIBLE }
    fun setEditable(value: Boolean) {
        editable = value
        scan.isEnabled = value && !running && photo != null
        for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = value
    }
    fun clear() {
        epoch++; photo = null; running = false; editable = false
        choices.removeAllViews(); message.text = ""; scan.isEnabled = false; visibility = GONE
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    private fun recognize() {
        val bitmap = photo ?: return
        if (!editable || running || bitmap.isRecycled) return
        val token = ++epoch
        running = true; scan.isEnabled = false; choices.removeAllViews()
        message.setText(R.string.ocr_loading)
        // Own pixels until the task completes, independent of host bitmap disposal.
        val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
        if (owned == null) { failed(token); return }
        val recognizer = try {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } catch (_: Exception) { owned.recycle(); failed(token); return }
        try {
            recognizer.process(InputImage.fromBitmap(owned, 0))
                .addOnSuccessListener { result ->
                    if (token != epoch || !isAttachedToWindow) return@addOnSuccessListener
                    val lines = OcrCandidates.lines(result.text)
                    message.setText(if (lines.isEmpty()) R.string.ocr_empty else R.string.ocr_review)
                    if (lines.isNotEmpty()) choices.addView(Button(context).apply {
                        setText(R.string.ocr_use_notes)
                        isEnabled = editable
                        setOnClickListener {
                            if (token == epoch && editable) applyNotes(lines.joinToString("\n"))
                        }
                    })
                    lines.forEach { line ->
                        choices.addView(Button(context).apply {
                            text = context.getString(R.string.ocr_use_name, line)
                            isEnabled = editable
                            setOnClickListener {
                                if (token == epoch && editable) applyName(line)
                            }
                        })
                    }
                }
                .addOnFailureListener { failed(token) }
                .addOnCompleteListener {
                    recognizer.close(); owned.recycle()
                    if (token == epoch) { running = false; setEditable(editable) }
                }
        } catch (_: Exception) {
            recognizer.close(); owned.recycle(); failed(token)
        }
    }
    private fun failed(token: Int) {
        if (token != epoch) return
        running = false; message.setText(R.string.ocr_failed); setEditable(editable)
    }
}
