import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceTask
import org.gradle.kotlin.dsl.getByType

fun Any.setProperty(name: String, value: Any) {
    javaClass.methods.single { method -> method.name == name && method.parameterCount == 1 }.invoke(this, value)
}

val detektExtension = extensions.getByName("detekt")
detektExtension.setProperty("setBuildUponDefaultConfig", true)
detektExtension.setProperty("setParallel", true)
val detektConfig = detektExtension.javaClass.getMethod("getConfig").invoke(detektExtension) as ConfigurableFileCollection
detektConfig.setFrom(layout.projectDirectory.file("config/detekt/detekt.yml"))

val sourceSets = extensions.getByType<SourceSetContainer>()
tasks.named("detekt") {
    (this as SourceTask).source(layout.projectDirectory.dir("src/integrationTest/kotlin"))
    source(layout.projectDirectory.dir("src/nativeTest/kotlin"))

    val classpath = javaClass.getMethod("getClasspath").invoke(this) as ConfigurableFileCollection
    classpath.setFrom(
        configurations.named("compileClasspath"),
        configurations.named("testCompileClasspath"),
        configurations.named("integrationTestCompileClasspath"),
        sourceSets.getByName("main").output,
        sourceSets.getByName("test").output,
    )

    @Suppress("UNCHECKED_CAST")
    val jvmTarget = javaClass.getMethod("getJvmTarget").invoke(this) as Property<String>
    jvmTarget.set("21")
}
