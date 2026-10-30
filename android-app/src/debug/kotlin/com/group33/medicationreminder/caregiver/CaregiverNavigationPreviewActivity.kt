package com.group33.medicationreminder.caregiver

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Debug-only launcher for local UI review; no server session or care relationship is created. */
class CaregiverNavigationPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, CaregiverHomeActivity::class.java).apply {
            if (!this@CaregiverNavigationPreviewActivity.intent.getBooleanExtra("NO_ELDER_PREVIEW", false)) {
                putExtra("com.group33.medicationreminder.LOCAL_NAV_DEMO", true)
            }
        })
        finish()
    }
}
