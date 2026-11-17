package com.group33.medicationreminder.caregiver.plan

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.constraintlayout.widget.ConstraintLayout
import com.group33.medicationreminder.R

object PlanEditorDebugEntry {
    fun install(activity: Activity, root: ViewGroup) {
        val button = Button(activity).apply {
            id = View.generateViewId()
            setText(R.string.c2_debug_open)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { activity.startActivity(Intent(activity, PlanEditorDebugActivity::class.java)) }
        }
        // Put the test entry before the existing demo content; no overlap on small screens.
        if (root is ConstraintLayout) {
            val originalTop = root.getChildAt(0)
            root.addView(button, ConstraintLayout.LayoutParams(0, -2).apply {
                topToTop = 0; startToStart = 0; endToEnd = 0
            })
            (originalTop?.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                topToTop = -1; topToBottom = button.id
            }
            originalTop?.requestLayout()
        } else {
            // The C1 navigation shell uses a vertical LinearLayout rather than the preview root.
            root.addView(button, 0, ViewGroup.LayoutParams(-1, -2))
        }
    }
}
