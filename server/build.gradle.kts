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

// Administrative tool, deliberately absent from the application runtime/bootJar.
val schemaMigration = sourceSets.create("schemaMigration")

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
    implementation(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.postgresql)
    add(schemaMigration.implementationConfigurationName, libs.flyway.core)
    add(schemaMigration.implementationConfigurationName, libs.flyway.postgresql)
    add(schemaMigration.implementationConfigurationName, libs.postgresql)
    add(schemaMigration.implementationConfigurationName, kotlin("stdlib"))
    testImplementation(schemaMigration.output)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
}

val schemaMigrationJar = tasks.register<Jar>("schemaMigrationJar") {
    archiveClassifier.set("schema-migration")
    from(schemaMigration.output)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<Zip>("schemaMigrationDist") {
    description = "Package the separate, explicitly invoked schema administration tool"
    group = "distribution"
    archiveFileName.set("group33-schema-migration.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(schemaMigrationJar) { into("lib") }
    from(configurations[schemaMigration.runtimeClasspathConfigurationName]) { into("lib") }
    inputs.file("src/schemaMigration/launcher/schema-migration")
    from("src/schemaMigration/launcher") {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

listOf("schemaMigrate" to "migrate", "schemaValidate" to "validate").forEach { (task, command) ->
    tasks.register<JavaExec>(task) {
        description = "Explicitly $command the privately configured PostgreSQL schema; never runs at server startup"
        group = "database"
        classpath = schemaMigration.runtimeClasspath
        mainClass.set("com.group33.server.migration.SchemaMigrationCli")
        args(command)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processTestResources {
    from(rootProject.file("docs/contracts/examples.json")) { into("contracts") }
}

tasks.test { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("postgresIntegrationTest") {
    description = "Run pairing Store tests against an explicitly configured private PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(!System.getenv("S2_PG_INTEGRATION_URL").isNullOrBlank()) {
            "Set explicit local PostgreSQL integration settings; these tests do not silently skip"
        }
        systemProperty("s2.postgres.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
}
