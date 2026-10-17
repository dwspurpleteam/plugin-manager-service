import org.gradle.kotlin.dsl.sourceSets

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.allopen") version "2.4.10"
    id("io.quarkus")
    alias(libs.plugins.io.github.mvettosi.tomlify)
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId = project.property("quarkusPlatformGroupId").toString()
val quarkusPlatformArtifactId = project.property("quarkusPlatformArtifactId").toString()
val quarkusPlatformVersion = project.property("quarkusPlatformVersion").toString()
val quarkusPlatformCoordinates = "$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"

val jooqGenerator = sourceSets.create("jooqGenerator")
val migrationsDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val jooqOutputDirectory = layout.projectDirectory.dir("src/quarkus-generated-sources/java")

dependencies {
    implementation(enforcedPlatform(quarkusPlatformCoordinates))

    // Kotlin и CDI
    implementation(libs.io.quarkus.quarkus.kotlin)
    implementation(libs.org.jetbrains.kotlin.kotlin.stdlib.jdk8)
    implementation(libs.io.quarkus.quarkus.arc)

    // Входящие REST-запросы и JSON
    implementation(libs.io.quarkus.quarkus.rest.kotlin)
    implementation(libs.io.quarkus.quarkus.rest.jackson)

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

    testImplementation(libs.io.quarkus.quarkus.junit)
    testImplementation(libs.io.rest.assured.rest.assured)
    testImplementation(libs.org.testcontainers.testcontainers.postgresql)

    add(jooqGenerator.implementationConfigurationName, enforcedPlatform(quarkusPlatformCoordinates))
    add(jooqGenerator.implementationConfigurationName, libs.org.jooq.jooq.codegen)
    add(jooqGenerator.implementationConfigurationName, libs.org.flywaydb.flyway.core)
    add(jooqGenerator.implementationConfigurationName, libs.org.flywaydb.flyway.database.postgresql)
    add(jooqGenerator.implementationConfigurationName, libs.org.postgresql.postgresql)
    add(jooqGenerator.implementationConfigurationName, libs.org.testcontainers.testcontainers.postgresql)
    add(jooqGenerator.runtimeOnlyConfigurationName, libs.org.slf4j.slf4j.simple)
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
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        javaParameters = true
    }
}

sourceSets.main {
    java.srcDir(jooqOutputDirectory)
}

val generateJooq = tasks.register<JavaExec>("generateJooq") {
    group = "code generation"
    description = "Migrates a Testcontainers PostgreSQL database and generates jOOQ sources."

    dependsOn(jooqGenerator.classesTaskName)
    classpath = jooqGenerator.runtimeClasspath
    mainClass = "ru.itmo.pluginmanager.codegen.JooqGenerator"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }

    args(
        migrationsDirectory.asFile.absolutePath,
        jooqOutputDirectory.asFile.absolutePath,
        "ru.itmo.pluginmanager.jooq.generated"
    )

    inputs.dir(migrationsDirectory)
    inputs.files(jooqGenerator.allSource)
    outputs.dir(jooqOutputDirectory)
}

tasks.named("compileJava") {
    dependsOn(generateJooq)
}

tasks.named("compileKotlin") {
    dependsOn(generateJooq)
}
