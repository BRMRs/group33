package com.group33.medicationreminder.caregiver

internal fun createCaregiverNavigationContextSource(activity: androidx.appcompat.app.AppCompatActivity, localDemo: Boolean): CaregiverNavigationContextSource =
    com.group33.medicationreminder.integration.realCaregiverContextSource(activity)
