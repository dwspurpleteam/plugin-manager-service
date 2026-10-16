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

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(
        enforcedPlatform(
            "$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"
        )
    )

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
}

group = "ru.itmo"
version = "1.0.0-SNAPSHOT"

java {
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
