package ru.itmo.pluginmanager.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class PluginManifest(
    @get:JsonProperty("manifest-version") @param:JsonProperty("manifest-version") val manifestVersion: String,
    @get:JsonProperty("plugin-id") @param:JsonProperty("plugin-id") val pluginId: String,
    @get:JsonProperty("plugin-version") @param:JsonProperty("plugin-version") val pluginVersion: String,
    val name: String,
    val description: String,
    val frontend: PluginFrontend,
    val backend: PluginBackend,
)

data class PluginFrontend(
    @get:JsonProperty("sdk-version") @param:JsonProperty("sdk-version") val sdkVersion: String,
    @get:JsonProperty("url-web") @param:JsonProperty("url-web") val urlWeb: String,
)

data class PluginBackend(
    @get:JsonProperty("sdk-version") @param:JsonProperty("sdk-version") val sdkVersion: String,
    @get:JsonProperty("health-url") @param:JsonProperty("health-url") val healthUrl: String,
    val events: PluginEvents,
    val media: PluginMedia,
)

data class PluginEvents(
    @get:JsonProperty("event-url") @param:JsonProperty("event-url") val eventUrl: String,
    @get:JsonProperty("available-event-types")
    @param:JsonProperty("available-event-types")
    val availableEventTypes: Set<String>? = null,
    @get:JsonProperty("available-event-groups")
    @param:JsonProperty("available-event-groups")
    val availableEventGroups: Set<String>? = null,
)

data class PluginMedia(val input: Set<MediaType>, val output: Set<MediaType>)

data class PluginConfiguration(val selection: PluginSelection, val manifest: PluginManifest)
