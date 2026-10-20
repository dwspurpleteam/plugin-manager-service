package ru.itmo.pluginmanager.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class PluginManifest(
    @param:JsonProperty("manifest-version") val manifestVersion: String,
    @param:JsonProperty("plugin-id") val pluginId: String,
    @param:JsonProperty("plugin-version") val pluginVersion: String,
    val name: String,
    val description: String,
    val frontend: PluginFrontend,
    val backend: PluginBackend,
)

data class PluginFrontend(
    @param:JsonProperty("sdk-version") val sdkVersion: String,
    @param:JsonProperty("url-web") val urlWeb: String,
)

data class PluginBackend(
    @param:JsonProperty("sdk-version") val sdkVersion: String,
    @param:JsonProperty("health-url") val healthUrl: String,
    val events: PluginEvents,
    val media: PluginMedia,
)

data class PluginEvents(
    @param:JsonProperty("event-url") val eventUrl: String,
    @param:JsonProperty("available-event-types") val availableEventTypes: Set<String>? = null,
    @param:JsonProperty("available-event-groups") val availableEventGroups: Set<String>? = null,
)

data class PluginMedia(val input: Set<MediaType>, val output: Set<MediaType>)

data class PluginConfiguration(val selection: PluginSelection, val manifest: PluginManifest)
