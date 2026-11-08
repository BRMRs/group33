package com.group33.medicationreminder.auth

import com.group33.medicationreminder.BuildConfig
import java.net.URI

/** Client composition only. The old trial wire is preserved; proposed binding fields are not invented. */
internal fun createFusionRepository(sessions: PhoneSessionAccess): PhoneAuthRepository = try {
    val uri = URI(BuildConfig.PHONE_AUTH_BASE_URL)
    val local = BuildConfig.DEBUG && BuildConfig.PHONE_AUTH_LOCAL_TRIAL && uri.scheme == "http" &&
        uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    if (!BuildConfig.DEBUG || (!local && !BuildConfig.PHONE_AUTH_PRIVACY_POLICY_URL.startsWith("https://"))) {
        UnconfiguredPhoneAuthRepository
    } else FusionPhoneAuthRepository(FusionServerClient(uri.toString(), local), sessions)
} catch (_: Exception) { UnconfiguredPhoneAuthRepository }
