package ru.itmo.pluginmanager.application

import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.RegistrationResult
import ru.itmo.pluginmanager.domain.validateIdentifier
import ru.itmo.pluginmanager.persistence.ActivationStore
import java.util.UUID

@ApplicationScoped
class ActivationServiceImpl(private val store: ActivationStore, private val config: PluginManagerConfig) :
    ActivationService {
    override suspend fun register(request: ActivationRequest): RegistrationResult {
        listOfNotNull(
            request.callId,
            request.pluginId,
            request.requestedBy,
            request.idempotencyKey,
            request.pluginVersion,
            request.pluginRegistryId,
        ).forEach(::validateIdentifier)
        request.pluginRegistryId?.let {
            if (it !in config.registries()) throw PluginManagerException(ErrorCode.UNKNOWN_REGISTRY, "Unknown registry")
        }
        return store.register(request)
    }

    override suspend fun get(activationId: UUID): Activation = store.find(activationId)
        ?: throw PluginManagerException(ErrorCode.ACTIVATION_NOT_FOUND, "Activation not found")

    override suspend fun signalRoute(activationId: UUID): ActivationRoute {
        requireActive(get(activationId))
        val route = store.route(activationId)
        // A concurrent failure and route cleanup must not be reported as a broken ACTIVE invariant.
        requireActive(get(activationId))
        return route ?: throw PluginManagerException(ErrorCode.INTERNAL_ERROR, "Active route is missing")
    }

    override suspend fun mediaTypes(activationId: UUID) = signalRoute(activationId).mediaTypes

    private fun requireActive(activation: Activation) {
        val code = when (activation.state) {
            ActivationState.ACTIVE -> return
            ActivationState.ACTIVATING -> ErrorCode.ACTIVATION_NOT_READY
            ActivationState.FAILED -> ErrorCode.ACTIVATION_FAILED
            else -> ErrorCode.ACTIVATION_NOT_ACTIVE
        }
        throw PluginManagerException(code, "Activation is not active")
    }
}
