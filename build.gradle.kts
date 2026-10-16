plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

tasks.register("gate1Check") {
    group = "verification"
    description = "Verify Gate 1 contracts, transport, Android JVM tests, lint and build artifacts; not human sign-off."
    dependsOn(
        ":shared:test",
        ":server:test",
        ":server:bootJar",
        ":android-app:testDebugUnitTest",
        ":android-app:lintDebug",
        ":android-app:assembleDebug",
    )
}
