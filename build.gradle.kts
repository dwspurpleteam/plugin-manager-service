import org.gradle.kotlin.dsl.sourceSets
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.allopen)
    alias(libs.plugins.io.quarkus)
    alias(libs.plugins.dev.detekt)
    alias(libs.plugins.io.github.mvettosi.tomlify)
}

repositories {
    mavenCentral()
    mavenLocal()
}

apply(from = "scripts/detekt.gradle.kts")
apply(from = "scripts/generate-jooq.gradle.kts")
apply(from = "scripts/generate-protobuf.gradle.kts")

val integrationTest = sourceSets.getByName("integrationTest")
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
integrationTest.compileClasspath += sourceSets.main.get().output
integrationTest.runtimeClasspath += sourceSets.main.get().output
integrationTest.compileClasspath += sourceSets.test.get().output
integrationTest.runtimeClasspath += sourceSets.test.get().output

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs JVM integration tests against isolated infrastructure."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    dependsOn("quarkusBuild")
}

dependencies {
    implementation(enforcedPlatform(libs.io.quarkus.platform.quarkus.bom))

    // Kotlin и CDI
    implementation(libs.io.quarkus.quarkus.kotlin)
    implementation(libs.org.jetbrains.kotlin.kotlin.stdlib.jdk8)
    implementation(libs.io.quarkus.quarkus.arc)

    // Входящие REST-запросы и JSON
    implementation(libs.io.quarkus.quarkus.rest.kotlin)
    implementation(libs.io.quarkus.quarkus.rest.jackson)
    implementation(libs.io.quarkus.quarkus.smallrye.openapi)

    // Исходящие REST-запросы
    implementation(libs.io.quarkus.quarkus.rest.client.jackson)

    // gRPC
    implementation(libs.io.quarkus.quarkus.grpc)
    implementation(libs.io.smallrye.reactive.mutiny.kotlin)

    // Миграции через JDBC
    implementation(libs.io.quarkus.quarkus.flyway)
    implementation(libs.org.flywaydb.flyway.database.postgresql)
    implementation(libs.io.quarkus.quarkus.jdbc.postgresql)

    // Планировщик
    implementation(libs.io.quarkus.quarkus.quartz)

    // jOOQ runtime и coroutine API
    implementation(libs.org.jooq.jooq)
    implementation(libs.org.jooq.jooq.kotlin)
    implementation(libs.org.jooq.jooq.kotlin.coroutines)

    // Реактивный доступ к PostgreSQL
    implementation(libs.org.postgresql.r2dbc.postgresql)
    implementation(libs.io.r2dbc.r2dbc.pool)
    implementation(libs.org.jetbrains.kotlinx.kotlinx.coroutines.reactive)
    implementation(libs.io.quarkus.quarkus.redis.client)
    implementation(libs.com.fasterxml.jackson.module.kotlin)

    testImplementation(libs.io.quarkus.quarkus.junit)
    testImplementation(libs.io.rest.assured.rest.assured)
    testImplementation(libs.org.testcontainers.testcontainers.postgresql)
    testImplementation(libs.org.testcontainers.testcontainers)

    detektPlugins(libs.dev.detekt.rules.ktlint.wrapper)

}

val lintDependencyDeclarations by tasks.registering {
    group = "verification"
    description = "Rejects string dependency coordinates in Gradle Kotlin DSL scripts."

    val gradleScripts = files(
        layout.projectDirectory.file("build.gradle.kts"),
        layout.projectDirectory.file("settings.gradle.kts"),
        layout.projectDirectory.dir("scripts").asFileTree.matching {
            include("*.gradle.kts")
        },
    )
    inputs.files(gradleScripts)

    doLast {
        val directStringNotation = Regex(
            """(?m)^\s*(?:api|implementation|compileOnly|runtimeOnly|annotationProcessor|classpath|kapt|ksp|detektPlugins|[A-Za-z][A-Za-z0-9]*(?:Api|Implementation|CompileOnly|RuntimeOnly))\s*\(\s*(?:(?:enforcedPlatform|platform)\s*\(\s*)?[\"'][^\"'\r\n]+:[^\"'\r\n]+[\"']"""
        )
        val addStringNotation = Regex(
            """(?m)^\s*add\s*\(\s*[^,]+,\s*(?:(?:enforcedPlatform|platform)\s*\(\s*)?[\"'][^\"'\r\n]+:[^\"'\r\n]+[\"']"""
        )
        val namedStringNotation = Regex(
            """(?m)^\s*(?:api|implementation|compileOnly|runtimeOnly|annotationProcessor|classpath|kapt|ksp|detektPlugins|[A-Za-z][A-Za-z0-9]*(?:Api|Implementation|CompileOnly|RuntimeOnly))\s*\([^)]*\b(?:group|name|module)\s*=\s*[\"']"""
        )

        val violations = gradleScripts.files.flatMap { script ->
            val content = script.readText()
            (directStringNotation.findAll(content) +
                    addStringNotation.findAll(content) +
                    namedStringNotation.findAll(content))
                .map { match ->
                    val line = content.take(match.range.first).count { it == '\n' } + 1
                    "${script.relativeTo(rootDir)}:$line"
                }
                .toList()
        }

        check(violations.isEmpty()) {
            "Use the version catalog instead of string dependency coordinates:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(lintDependencyDeclarations)
    dependsOn(integrationTestTask)
}

group = "ru.itmo"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        javaParameters = true
    }
}

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/protobuf/main/kotlin"))
}
