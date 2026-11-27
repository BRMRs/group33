package com.group33.medicationreminder.auth

import java.net.URI

/** Checks address syntax only; publication and availability remain team configuration gates. */
internal fun isValidPhonePrivacyPolicyUrl(value: String): Boolean {
    if (value.any { it == '"' || it == '\\' || it.isISOControl() }) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port in 1..65535)
}
