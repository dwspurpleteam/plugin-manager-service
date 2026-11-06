import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

val sourceSets = extensions.getByType<SourceSetContainer>()
val javaToolchains = extensions.getByType<JavaToolchainService>()
val libraries = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jooqGenerator = sourceSets.create("jooqGenerator")
val migrationsDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val jooqOutputDirectory = layout.buildDirectory.dir("generated/sources/jooq/main/java")

dependencies {
    add(jooqGenerator.implementationConfigurationName, enforcedPlatform(libraries.findLibrary("io.quarkus.platform.quarkus.bom").get()))
    add(jooqGenerator.implementationConfigurationName, libraries.findLibrary("org.jooq.jooq.codegen").get())
    add(jooqGenerator.implementationConfigurationName, libraries.findLibrary("org.flywaydb.flyway.core").get())
    add(jooqGenerator.implementationConfigurationName, libraries.findLibrary("org.flywaydb.flyway.database.postgresql").get())
    add(jooqGenerator.implementationConfigurationName, libraries.findLibrary("org.postgresql.postgresql").get())
    add(jooqGenerator.implementationConfigurationName, libraries.findLibrary("org.testcontainers.testcontainers.postgresql").get())
    add(jooqGenerator.runtimeOnlyConfigurationName, libraries.findLibrary("org.slf4j.slf4j.simple").get())
}

sourceSets.getByName("main").java.srcDir(jooqOutputDirectory)

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
        jooqOutputDirectory.get().asFile.absolutePath,
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
