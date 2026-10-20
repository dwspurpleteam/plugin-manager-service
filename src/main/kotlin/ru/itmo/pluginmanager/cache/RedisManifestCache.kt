package ru.itmo.pluginmanager.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginSelection
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class RedisManifestCache(
    redis: ReactiveRedisDataSource,
    private val mapper: ObjectMapper,
    private val config: PluginManagerConfig,
) : ManifestCache {
    private val values = redis.value(String::class.java)

    override suspend fun get(selection: PluginSelection): PluginManifest? = bestEffort {
        values.get(key(selection)).awaitSuspending()?.let { mapper.readValue(it, PluginManifest::class.java) }
    }

    override suspend fun put(selection: PluginSelection, manifest: PluginManifest) {
        bestEffort {
            values.psetex(key(selection), config.cache().ttl().toMillis(), mapper.writeValueAsString(manifest))
                .awaitSuspending()
        }
    }

    private fun key(selection: PluginSelection): String = "manifest:v1:" + listOf(
        selection.registryId,
        selection.pluginId,
        selection.pluginVersion,
    ).joinToString(
        ":",
    ) { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }

    private suspend fun <T> bestEffort(action: suspend () -> T): T? = try {
        withTimeoutOrNull(config.cache().timeout().toMillis().milliseconds) { action() }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }
}
