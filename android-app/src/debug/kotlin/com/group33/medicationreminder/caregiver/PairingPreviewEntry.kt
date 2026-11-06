package com.group33.medicationreminder.caregiver

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

/** Debug-only reviewer route; never used as a real pairing session. */
internal object PairingPreviewEntry {
    fun open(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, PairingRequestPreviewActivity::class.java))
    }
}
