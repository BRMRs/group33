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
    implementation(libs.aliyun.dypns)
    implementation("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processTestResources {
    from(rootProject.file("docs/contracts/examples.json")) { into("contracts") }
}

tasks.test { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("phonePostgresIntegrationTest") {
    description = "Run phone account checks against the explicitly configured dedicated local PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(System.getenv("S2_PHONE_PG_URL") == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local") {
            "Explicit dedicated loopback phone PostgreSQL settings are required"
        }
        systemProperty("s2.phone.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
}
