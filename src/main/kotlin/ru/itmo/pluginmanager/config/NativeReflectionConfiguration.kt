package ru.itmo.pluginmanager.config

import io.quarkus.runtime.annotations.RegisterForReflection
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.PluginBackend
import ru.itmo.pluginmanager.domain.PluginConfiguration
import ru.itmo.pluginmanager.domain.PluginEvents
import ru.itmo.pluginmanager.domain.PluginFrontend
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginMedia
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.jooq.generated.tables.records.ActivationRouteRecord
import ru.itmo.pluginmanager.jooq.generated.tables.records.ActivationWorkRecord
import ru.itmo.pluginmanager.jooq.generated.tables.records.PluginActivationRecord
import ru.itmo.pluginmanager.registry.RegistryPage
import ru.itmo.pluginmanager.registry.RegistryPlugin

@RegisterForReflection(
    targets = [
        ActivationRouteRecord::class,
        ActivationWorkRecord::class,
        PluginActivationRecord::class,
        MediaType::class,
        PluginBackend::class,
        PluginConfiguration::class,
        PluginEvents::class,
        PluginFrontend::class,
        PluginManifest::class,
        PluginMedia::class,
        PluginSelection::class,
        RegistryPage::class,
        RegistryPlugin::class,
    ],
    methods = true,
    fields = true,
)
class NativeReflectionConfiguration
