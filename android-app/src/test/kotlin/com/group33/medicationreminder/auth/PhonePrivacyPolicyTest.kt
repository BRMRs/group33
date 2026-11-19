package com.group33.medicationreminder.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePrivacyPolicyTest {
    @Test fun acceptsHttpsPolicyPathsQueriesAndFragments() {
        listOf("https://example.test", "https://example.test/privacy?lang=zh#terms",
            "https://example.test:8443/privacy", "https://example.test/privacy%20policy")
            .forEach { assertTrue(it, isValidPhonePrivacyPolicyUrl(it)) }
    }

    @Test fun rejectsMissingHostMalformedAddressesAndOtherSchemes() {
        listOf("", "https://", "https:///privacy", "https:privacy", "/privacy",
            "http://example.test/privacy", "javascript:alert(1)", "https://exa mple.test",
            " https://example.test", "https://example.test:bad/privacy")
            .forEach { assertFalse(it, isValidPhonePrivacyPolicyUrl(it)) }
    }

    @Test fun rejectsCredentialsAndInvalidPorts() {
        listOf("https://name:password@example.test/privacy", "https://name@example.test",
            "https://example.test:0/privacy", "https://example.test:65536/privacy")
            .forEach { assertFalse(it, isValidPhonePrivacyPolicyUrl(it)) }
    }

    @Test fun rejectsControlsAndBuildConfigEscapes() {
        listOf("https://example.test/\nprivacy", "https://example.test/\tprivacy",
            "https://example.test/\"privacy", "https://example.test/\\privacy")
            .forEach { assertFalse(it, isValidPhonePrivacyPolicyUrl(it)) }
    }
}
