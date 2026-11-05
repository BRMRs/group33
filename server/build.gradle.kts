plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.group33"
version = "0.1.0"

// Boot's default serialization BOM must not downgrade serializers compiled by :shared.
extra["kotlin-serialization.version"] = libs.versions.serialization.get()

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("org.postgresql:postgresql:42.7.14")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processTestResources {
    from(rootProject.file("docs/contracts/examples.json")) { into("contracts") }
}

// This dedicated suite requires an explicitly selected disposable local database.
tasks.test { useJUnitPlatform { excludeTags("postgres") } }
tasks.register<Test>("postgresIntegrationTest") {
    description = "Run capability persistence tests against an explicit private PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(!System.getenv("S2_PG_INTEGRATION_URL").isNullOrBlank()) {
            "Set explicit local PostgreSQL integration settings; tests do not silently skip"
        }
        systemProperty("s2.postgres.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
}
