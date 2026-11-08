import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.group33.medicationreminder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.group33.medicationreminder"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PHONE_AUTH_BASE_URL", "\"\"")
        buildConfigField("String", "PHONE_AUTH_PRIVACY_POLICY_URL", "\"\"")
        buildConfigField("boolean", "PHONE_AUTH_LOCAL_TRIAL", "false")
    }

    buildTypes {
        debug {
            val phoneUrl = providers.gradleProperty("phoneAuthBaseUrl").getOrElse("").trimEnd('/')
            val serverUrl = providers.gradleProperty("debugServerUrl").getOrElse(phoneUrl.ifBlank { "http://10.0.2.2:18082" }).trimEnd('/')
            require(serverUrl.matches(Regex("https?://[A-Za-z0-9.:-]+/?")))
            require(phoneUrl.isBlank() || phoneUrl == serverUrl) { "Phone authentication and business must use the same server origin" }
            val policyUrl = providers.gradleProperty("phoneAuthPrivacyPolicyUrl").getOrElse("")
            require(policyUrl.isEmpty() || (policyUrl.startsWith("https://") && !policyUrl.any { it == '"' || it == '\\' || it.isISOControl() }))
            val localTrial = providers.gradleProperty("phoneAuthLocalTrial").getOrElse("false")
            require(localTrial == "true" || localTrial == "false")
            buildConfigField("String", "PHONE_AUTH_BASE_URL", "\"$phoneUrl\"")
            buildConfigField("String", "PHONE_AUTH_PRIVACY_POLICY_URL", "\"$policyUrl\"")
            buildConfigField("boolean", "PHONE_AUTH_LOCAL_TRIAL", localTrial)
            buildConfigField("String", "DEBUG_SERVER_URL", "\"$serverUrl\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Optional team test key is supplied privately. No key/password is stored in the repository.
    providers.environmentVariable("GROUP33_TEST_KEYSTORE").orNull?.let { path ->
        val team = signingConfigs.create("teamTest") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("GROUP33_TEST_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("GROUP33_TEST_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("GROUP33_TEST_KEY_PASSWORD").orNull
        }
        buildTypes.getByName("debug").signingConfig = team
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("docs"))
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":shared"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(files("libs/fusionauth-1.2.16-online-release.aar"))
    debugImplementation(files("libs/umeng-common-9.5.6.aar"))
    debugImplementation(files("libs/umeng-asms-v1.8.0.aar"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<Test>().configureEach {
    systemProperty("group33.androidModuleDir", projectDir.absolutePath)
}

val verifyPhoneTestConfiguration = tasks.register("verifyPhoneTestConfiguration") {
    group = "verification"
    description = "Validate the shared phone-service origin, policy and registered signing certificate"
    dependsOn("validateSigningDebug")
    doLast {
        val base = providers.gradleProperty("phoneAuthBaseUrl").orNull.orEmpty()
        val uri = runCatching { URI(base) }.getOrNull()
        val local = providers.gradleProperty("phoneAuthLocalTrial").getOrElse("false") == "true" &&
            uri?.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(uri?.host != null && (uri.scheme == "https" || local)) {
            "Set phoneAuthBaseUrl to the team HTTPS phone service (loopback HTTP is local-trial only)"
        }
        require(local || providers.gradleProperty("phoneAuthPrivacyPolicyUrl").orNull.orEmpty().startsWith("https://")) {
            "Set phoneAuthPrivacyPolicyUrl for the team test build"
        }
        val expected = providers.gradleProperty("phoneAuthSigningMd5").orNull.orEmpty().lowercase()
        require(expected.matches(Regex("[a-f0-9]{32}"))) { "Set phoneAuthSigningMd5 to the certificate registered for the Fusion scheme" }
        val signing = checkNotNull(android.buildTypes.getByName("debug").signingConfig)
        val expectedPackage = providers.gradleProperty("phoneAuthPackageName").getOrElse("com.group33.medicationreminder")
        require(android.defaultConfig.applicationId.orEmpty() + android.buildTypes.getByName("debug").applicationIdSuffix.orEmpty() == expectedPackage) {
            "The test application ID does not match the registered Fusion package"
        }
        val keyStore = KeyStore.getInstance(checkNotNull(signing.storeFile), checkNotNull(signing.storePassword).toCharArray())
        val certificate = checkNotNull(keyStore.getCertificate(signing.keyAlias))
        val fingerprint = MessageDigest.getInstance("MD5").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        require(fingerprint == expected) { "The test signing certificate does not match the registered Fusion certificate" }
        println("Phone service configuration and public signing fingerprint verified")
    }
}
tasks.register("assemblePhoneTest") {
    group = "build"
    description = "Build the configured, certificate-checked APK for team phone sign-in testing"
    dependsOn(verifyPhoneTestConfiguration, "assembleDebug")
}

