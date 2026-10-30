package com.group33.medicationreminder.auth

import android.content.Context
import com.group33.medicationreminder.BuildConfig

/** Release builds cannot select or contain the local demonstration adapter. */
internal fun createPhoneAuthRepository(localDemoRequested: Boolean, context: Context): PhoneAuthRepository = try {
    if (BuildConfig.PHONE_AUTH_BASE_URL.isBlank() || !BuildConfig.PHONE_AUTH_PRIVACY_POLICY_URL.startsWith("https://")) {
        UnconfiguredPhoneAuthRepository
    } else {
        FusionPhoneAuthRepository(context, BuildConfig.PHONE_AUTH_BASE_URL, allowHttp = false)
    }
} catch (_: Exception) { UnconfiguredPhoneAuthRepository }
