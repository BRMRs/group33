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
        val authUrl = providers.gradleProperty("phoneAuthBaseUrl").orElse("").get()
        val privacyUrl = providers.gradleProperty("phoneAuthPrivacyPolicyUrl").orElse("").get()
        require(!authUrl.contains('"') && !authUrl.contains('\\') && !authUrl.contains('\n'))
        require(!privacyUrl.contains('"') && !privacyUrl.contains('\\') && !privacyUrl.contains('\n'))
        buildConfigField("String", "PHONE_AUTH_BASE_URL", "\"$authUrl\"")
        buildConfigField("String", "PHONE_AUTH_PRIVACY_POLICY_URL", "\"$privacyUrl\"")
        buildConfigField("boolean", "PHONE_AUTH_LOCAL_TRIAL", providers.gradleProperty("phoneAuthLocalTrial").orElse("false").get())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    // Alibaba's downloaded Fusion Auth bundle includes these local Android SDK artifacts.
    implementation(files("libs/fusionauth-1.2.16-online-release.aar"))
    implementation(files("libs/umeng-common-9.5.6.aar"))
    implementation(files("libs/umeng-asms-v1.8.0.aar"))
    implementation(libs.kotlinx.serialization.json)
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

