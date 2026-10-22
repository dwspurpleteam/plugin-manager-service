package ru.itmo.pluginmanager.worker.activation

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.pluginmanager.application.ConfigurationService
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.health.PluginHealthcheck
import ru.itmo.pluginmanager.persistence.ActivationStore
import ru.itmo.pluginmanager.persistence.WorkStore
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import ru.itmo.pluginmanager.worker.WorkerProcessor
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class DefaultActivationProcessor(
    private val store: ActivationStore,
    private val work: WorkStore,
    private val configurations: ConfigurationService,
    private val health: PluginHealthcheck,
    private val config: PluginManagerConfig,
    private val clock: Clock,
) : WorkerProcessor {
    override val kind = WorkKind.ACTIVATE

    override suspend fun process(lease: WorkLease) {
        if (lease.attemptCount > config.activation().attempts()) {
            store.fail(lease, ErrorCode.ACTIVATION_ATTEMPTS_EXHAUSTED)
            return
        }
        try {
            val completed = withTimeoutOrNull(
                config.worker().leaseDuration().minus(config.worker().pollInterval()).toMillis().milliseconds,
            ) {
                activate(lease)
                true
            }
            if (completed == null) failedAttempt(lease, ErrorCode.REGISTRY_UNAVAILABLE)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: PluginManagerException) {
            if (exception.code == ErrorCode.LEASE_LOST) throw exception
            failedAttempt(lease, exception.code)
        }
    }

    private suspend fun activate(lease: WorkLease) {
        val activation = store.find(lease.activationId) ?: return
        if (activation.state != ActivationState.ACTIVATING) return
        val route = store.route(lease.activationId) ?: run {
            val selection = activation.selection ?: configurations.resolve(
                activation.request.pluginId,
                activation.request.pluginVersion,
                activation.request.pluginRegistryId,
            ).also { store.pinSelection(lease, it) }
            val manifest = configurations.get(selection).manifest
            val allowedEventTypes = manifest.backend.events.availableEventTypes
            ActivationRoute(
                UUID.randomUUID(),
                lease.activationId,
                manifest.backend.events.eventUrl,
                allowedEventTypes,
                manifest.backend.events.availableEventGroups.takeIf { allowedEventTypes == null },
                manifest.backend.healthUrl,
                manifest.backend.media.input,
            ).also { store.saveRoute(lease, it) }
        }
        if (!health.check(route.healthcheckUrl)) {
            throw PluginManagerException(ErrorCode.HEALTHCHECK_FAILED, "Activation healthcheck failed")
        }
        sendInitialActivationMessage(route)
        store.activate(lease, clock.instant().plus(config.health().interval()))
    }

    private fun sendInitialActivationMessage(
        @Suppress("UNUSED_PARAMETER") route: ActivationRoute,
    ) = Unit

    private suspend fun failedAttempt(lease: WorkLease, code: ErrorCode) {
        val permanent = code in setOf(
            ErrorCode.PLUGIN_NOT_FOUND,
            ErrorCode.INVALID_MANIFEST,
            ErrorCode.REGISTRY_INVALID_RESPONSE,
            ErrorCode.UNKNOWN_REGISTRY,
            ErrorCode.INVALID_REQUEST,
        )
        if (permanent || lease.attemptCount >= config.activation().attempts()) {
            store.fail(lease, code)
        } else {
            val delay = config.activation().retryDelays()[lease.attemptCount - 1]
            work.retryActivation(lease, clock.instant().plus(delay), code)
        }
    }
}
