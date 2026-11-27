package com.group33.medicationreminder.auth

import com.group33.medicationreminder.BuildConfig
import java.net.URI

/** Bound #96/#144 wire only. Missing binding fails closed; never fall back to the old unbound trial. */
internal fun createFusionRepository(sessions: PhoneSessionAccess): PhoneAuthRepository = try {
    val uri = URI(BuildConfig.PHONE_AUTH_BASE_URL)
    val local = BuildConfig.DEBUG && BuildConfig.PHONE_AUTH_LOCAL_TRIAL && uri.scheme == "http" &&
        uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    if (!BuildConfig.DEBUG || (!local && !isValidPhonePrivacyPolicyUrl(BuildConfig.PHONE_AUTH_PRIVACY_POLICY_URL))) {
        UnconfiguredPhoneAuthRepository
    } else FusionPhoneAuthRepository(FusionServerClient(uri.toString(), local), sessions)
} catch (_: Exception) { UnconfiguredPhoneAuthRepository }
