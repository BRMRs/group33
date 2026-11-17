package com.group33.medicationreminder.caregiver

import kotlinx.coroutines.flow.flowOf

/** Explicit debug preview only; never exposes an authenticated or paired account. */
internal fun createCaregiverNavigationContextSource(activity: androidx.appcompat.app.AppCompatActivity, localDemo: Boolean): CaregiverNavigationContextSource =
    if (localDemo) CaregiverNavigationContextSource {
        flowOf(CaregiverNavigationContext.ActiveElder("local-preview-elder", "Demo elder"))
    } else com.group33.medicationreminder.integration.realCaregiverContextSource(activity)
