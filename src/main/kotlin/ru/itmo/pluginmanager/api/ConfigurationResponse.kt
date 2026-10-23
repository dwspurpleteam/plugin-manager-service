package ru.itmo.pluginmanager.api

import ru.itmo.pluginmanager.domain.PluginManifest

data class ConfigurationResponse(
    val pluginId: String,
    val pluginVersion: String,
    val registryId: String,
    val manifest: PluginManifest,
)
