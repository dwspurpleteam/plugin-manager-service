package ru.itmo.pluginmanager.registry

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestResponse
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginManifest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@ApplicationScoped
class HttpPluginRegistry(private val config: PluginManagerConfig, private val mapper: ObjectMapper) : PluginRegistry {
    private val clients = ConcurrentHashMap<String, RegistryHttpEndpoint>()

    private fun client(registryId: String): RegistryHttpEndpoint = clients.computeIfAbsent(registryId) {
        val registry = config.registries()[registryId]
            ?: throw PluginManagerException(ErrorCode.UNKNOWN_REGISTRY, "Unknown registry")
        QuarkusRestClientBuilder.newBuilder().baseUri(registry.url())
            .connectTimeout(config.http().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.http().requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .followRedirects(false).property("microprofile.rest.client.disable.default.mapper", true)
            .build(RegistryHttpEndpoint::class.java)
    }

    override suspend fun plugins(registryId: String, page: Int, limit: Int): RegistryPage<RegistryPlugin> {
        require(page > 0 && limit > 0)
        val result = read<RegistryPage<RegistryPlugin>> { client(registryId).plugins(page, limit) }
            ?: throw PluginManagerException(ErrorCode.REGISTRY_INVALID_RESPONSE, "Registry listing is unavailable")
        val invalidPage = result.page != page || result.limit != limit || result.totalItems < 0 || result.totalPages < 0
        val invalidItems =
            result.items.size > limit || result.items.any { it.id.isBlank() || it.latestVersion.isBlank() }
        if (invalidPage || invalidItems) {
            throw PluginManagerException(ErrorCode.REGISTRY_INVALID_RESPONSE, "Invalid registry pagination")
        }
        return result
    }

    override suspend fun versions(registryId: String, pluginId: String): List<String> =
        read<List<String>> { client(registryId).versions(pluginId) }
            ?: throw PluginManagerException(ErrorCode.PLUGIN_NOT_FOUND, "Plugin not found")

    override suspend fun manifest(registryId: String, pluginId: String, version: String): PluginManifest? =
        read<PluginManifest> { client(registryId).manifest(pluginId, version) }

    private inline fun <reified T> read(request: () -> RestResponse<String>): T? = try {
        request().use { response ->
            when {
                response.status == Response.Status.NOT_FOUND.statusCode -> null

                response.status >= Response.Status.INTERNAL_SERVER_ERROR.statusCode ->
                    throw PluginManagerException(ErrorCode.REGISTRY_UNAVAILABLE, "Registry unavailable")

                response.status != Response.Status.OK.statusCode ->
                    throw PluginManagerException(ErrorCode.REGISTRY_INVALID_RESPONSE, "Unexpected registry response")

                else -> mapper.readValue(response.entity, object : TypeReference<T>() {})
            }
        }
    } catch (exception: ProcessingException) {
        throw PluginManagerException(ErrorCode.REGISTRY_UNAVAILABLE, "Registry request failed", exception)
    } catch (exception: JsonProcessingException) {
        throw PluginManagerException(ErrorCode.REGISTRY_INVALID_RESPONSE, "Invalid registry JSON", exception)
    }

    @PreDestroy
    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
