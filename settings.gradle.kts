pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.jvm" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.plugin.allopen" ->
                    useModule("org.jetbrains.kotlin:kotlin-allopen:${requested.version}")
            }
        }
    }
}

rootProject.name = "plugin-manage-service"
