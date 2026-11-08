package ru.itmo.pluginmanager.activation

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginActivationMessage
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.validateHttpUrl
import java.net.URI
import java.util.concurrent.TimeUnit

@ApplicationScoped
class HttpPluginActivationNotifier(private val config: PluginManagerConfig) : PluginActivationNotifier {
    override suspend fun notify(endpoint: String, message: PluginActivationMessage) {
        validateHttpUrl(endpoint)
        try {
            QuarkusRestClientBuilder.newBuilder().baseUri(URI(endpoint))
                .connectTimeout(config.http().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.http().requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .property("microprofile.rest.client.disable.default.mapper", true)
                .build(ActivationHttpEndpoint::class.java).use { client ->
                    client.activate(message).use { response ->
                        if (response.status !in SUCCESSFUL_STATUS_RANGE) {
                            throw PluginManagerException(
                                ErrorCode.REGISTRY_UNAVAILABLE,
                                "Plugin activation message rejected",
                            )
                        }
                    }
                }
        } catch (exception: ProcessingException) {
            throw PluginManagerException(
                ErrorCode.REGISTRY_UNAVAILABLE,
                "Plugin activation message delivery failed",
            ).also { it.addSuppressed(exception) }
        }
    }

    private companion object {
        val SUCCESSFUL_STATUS_RANGE = 200..299
    }
}
