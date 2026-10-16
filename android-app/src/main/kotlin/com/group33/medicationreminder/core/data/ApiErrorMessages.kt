package com.group33.medicationreminder.core.data

import androidx.annotation.StringRes
import com.group33.medicationreminder.R

/** Server text is diagnostic only; UI selects localized resources using stable codes. */
object ApiErrorMessages {
    @StringRes
    fun resourceFor(code: String): Int = when (code) {
        "MALFORMED_REQUEST", "METHOD_NOT_ALLOWED", "UNSUPPORTED_MEDIA_TYPE" -> R.string.error_request
        "UNAUTHENTICATED" -> R.string.error_authentication
        "ROLE_NOT_ALLOWED" -> R.string.error_role
        "RESOURCE_NOT_ACCESSIBLE" -> R.string.error_resource
        "INSTALLATION_ROLE_CONFLICT" -> R.string.error_installation_role
        "VALIDATION_FAILED" -> R.string.error_validation
        "PAIRING_CODE_UNAVAILABLE" -> R.string.error_pairing_code
        "PAIRING_REQUEST_CLOSED" -> R.string.error_pairing_closed
        "ELDER_ALREADY_PAIRED" -> R.string.error_already_paired
        "PLAN_VERSION_CONFLICT" -> R.string.error_plan_version
        "IDEMPOTENCY_KEY_REUSED", "CAPABILITY_SEQUENCE_CONFLICT" -> R.string.error_command_conflict
        "OPERATION_IN_PROGRESS" -> R.string.error_operation_pending
        "PHOTO_NOT_READY" -> R.string.error_photo_not_ready
        "PHOTO_TOO_LARGE" -> R.string.error_photo_too_large
        "PHOTO_TYPE_UNSUPPORTED" -> R.string.error_photo_type
        "OVERLAPPING_PLAN" -> R.string.error_overlapping_plan
        "SNAPSHOT_RANGE_TOO_LARGE" -> R.string.error_snapshot_range
        "SERVICE_UNAVAILABLE", "RETRY_LATER" -> R.string.error_service
        else -> R.string.error_unknown
    }
}
