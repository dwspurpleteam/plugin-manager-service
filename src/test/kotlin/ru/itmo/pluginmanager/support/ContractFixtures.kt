package ru.itmo.pluginmanager.support

import io.smallrye.config.SmallRyeConfigBuilder
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.PluginBackend
import ru.itmo.pluginmanager.domain.PluginEvents
import ru.itmo.pluginmanager.domain.PluginFrontend
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginMedia
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

fun policy(overrides: Map<String, String> = emptyMap()): PluginManagerConfig {
    val properties = Properties()
    Files.newInputStream(Path.of("src/main/resources/application.properties")).use(properties::load)
    val defaults = properties.stringPropertyNames().filter { it.startsWith("plugin-manager.") }
        .associateWith(properties::getProperty) + overrides
    return SmallRyeConfigBuilder().withMapping(PluginManagerConfig::class.java).withDefaultValues(defaults)
        .build().getConfigMapping(PluginManagerConfig::class.java)
}

inline fun <reified T> component(name: String, vararg dependencies: Any): T {
    val type = try {
        Class.forName("ru.itmo.pluginmanager.$name")
    } catch (exception: ClassNotFoundException) {
        throw AssertionError("Contract implementation is missing: $name", exception)
    }
    val constructor = type.constructors.single { candidate ->
        candidate.parameterCount == dependencies.size && candidate.parameterTypes.zip(dependencies).all { (p, v) ->
            p.isInstance(v)
        }
    }
    return try {
        constructor.newInstance(*dependencies) as T
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
}

fun manifest(version: String = "1.0.0", health: String = "http://localhost/health"): PluginManifest = PluginManifest(
    "0.1",
    "plugin",
    version,
    "Example",
    "Description",
    PluginFrontend("1.0.0", "https://example.com/ui"),
    PluginBackend(
        "1.0.0",
        health,
        PluginEvents("https://example.com/signals", setOf("participant.joined")),
        PluginMedia(setOf(MediaType.AUDIO, MediaType.SCREEN_SHARE_AUDIO), setOf(MediaType.VIDEO)),
    ),
)
