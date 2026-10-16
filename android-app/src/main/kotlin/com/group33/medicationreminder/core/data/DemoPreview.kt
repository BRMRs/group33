package com.group33.medicationreminder.core.data

import java.time.Clock
import java.time.ZoneId

/** Process-local preview identities. They are never authenticated users or server authorization. */
object DemoPreview {
    const val ELDER_ID = "demo-elder"
    const val CAREGIVER_ID = "demo-caregiver"
    val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai"))
}
