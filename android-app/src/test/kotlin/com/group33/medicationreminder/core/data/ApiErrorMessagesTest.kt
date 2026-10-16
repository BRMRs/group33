package com.group33.medicationreminder.core.data

import com.group33.medicationreminder.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorMessagesTest {
    @Test
    fun actionableErrorCodesHaveDistinctLocalizedMappingsAndUnknownCodesFallBack() {
        assertEquals(R.string.error_authentication, ApiErrorMessages.resourceFor("UNAUTHENTICATED"))
        assertEquals(R.string.error_resource, ApiErrorMessages.resourceFor("RESOURCE_NOT_ACCESSIBLE"))
        assertEquals(R.string.error_validation, ApiErrorMessages.resourceFor("VALIDATION_FAILED"))
        assertEquals(R.string.error_installation_role, ApiErrorMessages.resourceFor("INSTALLATION_ROLE_CONFLICT"))
        assertEquals(R.string.error_unknown, ApiErrorMessages.resourceFor("FUTURE_UNKNOWN_CODE"))
    }

    @Test
    fun p0CodesMapToNonemptyEnglishAndChineseResourcesWithoutUsingServerMessages() {
        val expected = mapOf(
            "PAIRING_CODE_UNAVAILABLE" to "error_pairing_code",
            "PAIRING_REQUEST_CLOSED" to "error_pairing_closed",
            "ELDER_ALREADY_PAIRED" to "error_already_paired",
            "PLAN_VERSION_CONFLICT" to "error_plan_version",
            "IDEMPOTENCY_KEY_REUSED" to "error_command_conflict",
            "CAPABILITY_SEQUENCE_CONFLICT" to "error_command_conflict",
            "OPERATION_IN_PROGRESS" to "error_operation_pending",
            "PHOTO_NOT_READY" to "error_photo_not_ready",
            "PHOTO_TOO_LARGE" to "error_photo_too_large",
            "PHOTO_TYPE_UNSUPPORTED" to "error_photo_type",
            "OVERLAPPING_PLAN" to "error_overlapping_plan",
            "SNAPSHOT_RANGE_TOO_LARGE" to "error_snapshot_range",
        )
        val moduleDir = checkNotNull(System.getProperty("group33.androidModuleDir"))
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        for (locale in listOf("values", "values-zh")) {
            val nodes = factory.newDocumentBuilder().parse(java.io.File(moduleDir, "src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            expected.forEach { (code, resource) ->
                val resourceId = R.string::class.java.getField(resource).getInt(null)
                assertEquals(code, resourceId, ApiErrorMessages.resourceFor(code))
                org.junit.Assert.assertTrue("$locale:$resource", !strings[resource].isNullOrBlank())
            }
        }
    }
}
