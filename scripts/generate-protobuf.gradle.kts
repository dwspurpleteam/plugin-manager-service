import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

val sourceSets = extensions.getByType<SourceSetContainer>()
val libraries = extensions.getByType<VersionCatalogsExtension>().named("libs")
val easyPConfiguration = layout.projectDirectory.file("config/easyp.yaml")
val easyPJavaOutputDirectory = layout.buildDirectory.dir("generated/sources/protobuf/main/java")
val easyPKotlinOutputDirectory = layout.buildDirectory.dir("generated/sources/protobuf/main/kotlin")
val easyPExecutable = providers.gradleProperty("easyPExecutable")
    .orElse(providers.environmentVariable("EASYP_BIN"))
    .orElse(
        providers.provider {
            listOf("/opt/homebrew/bin/easyp", "/usr/local/bin/easyp")
                .firstOrNull { file(it).canExecute() }
                ?: "easyp"
        }
    )

dependencies {
    add("implementation", libraries.findLibrary("com.google.protobuf.protobuf.kotlin").get())
}

sourceSets.getByName("main").java.srcDir(easyPJavaOutputDirectory)

val generateProtoMessages = tasks.register<Exec>("generateProtoMessages") {
    group = "code generation"
    description = "Generates Kotlin and Java Protobuf message sources with EasyP."

    environment("EASYP_CFG", easyPConfiguration.asFile.absolutePath)
    commandLine(easyPExecutable.get(), "generate", "--path", "proto", "--root", layout.projectDirectory.asFile.absolutePath)

    inputs.dir(layout.projectDirectory.dir("proto"))
    inputs.file(easyPConfiguration)
    outputs.dirs(easyPJavaOutputDirectory, easyPKotlinOutputDirectory)
}

tasks.named("compileJava") {
    dependsOn(generateProtoMessages)
}

tasks.named("compileKotlin") {
    dependsOn(generateProtoMessages)
}
