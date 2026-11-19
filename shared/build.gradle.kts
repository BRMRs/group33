plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.processTestResources {
    from(rootProject.file("docs/contracts/examples.json")) { into("contracts") }
}


// One review fixture is consumed unchanged by all three runtime suites.
tasks.processTestResources {
    from(rootProject.file("docs/contracts/proposals/v2-acceptance-candidates.json")) { into("contracts/proposals") }
}
