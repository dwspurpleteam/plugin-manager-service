package ru.itmo.pluginmanager.support

import io.smallrye.config.SmallRyeConfigBuilder
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.PluginBackend
import ru.itmo.pluginmanager.domain.PluginEvents
import ru.itmo.pluginmanager.domain.PluginFrontend
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginMedia
import java.util.Properties

fun policy(overrides: Map<String, String> = emptyMap()): PluginManagerConfig {
    val properties = Properties()
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("application-test.properties")) {
        "Test configuration application-test.properties is missing"
    }.use(properties::load)
    val defaults = properties.stringPropertyNames().filter { it.startsWith("plugin-manager.") }
        .associateWith(properties::getProperty) + overrides
    return SmallRyeConfigBuilder().withMapping(PluginManagerConfig::class.java).withDefaultValues(defaults)
        .build().getConfigMapping(PluginManagerConfig::class.java)
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
