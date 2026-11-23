package ru.itmo.pluginmanager.worker.deactivation

import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.activation.PluginActivationNotifier
import ru.itmo.pluginmanager.application.ConfigurationService
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.PluginDeactivationMessage
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.persistence.ActivationStore
import ru.itmo.pluginmanager.persistence.WorkStore
import ru.itmo.pluginmanager.routes.ActivationSecrets
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import ru.itmo.pluginmanager.worker.WorkerProcessor
import java.time.Clock

@Suppress("LongParameterList")
@ApplicationScoped
class DefaultDeactivationProcessor(
    private val store: ActivationStore,
    private val work: WorkStore,
    private val configurations: ConfigurationService,
    private val notifier: PluginActivationNotifier,
    private val secrets: ActivationSecrets,
    private val config: PluginManagerConfig,
    private val clock: Clock,
) : WorkerProcessor {
    override val kind = WorkKind.DEACTIVATE

    override suspend fun process(lease: WorkLease) {
        val activation = store.find(lease.activationId)?.takeIf { it.state == ActivationState.STOPPING } ?: return
        try {
            deliver(activation, checkNotNull(lease.requestedBy))
        } catch (exception: PluginManagerException) {
            val delay = config.activation().retryDelays()
            val retryAfter = delay.getOrNull(lease.attemptCount - 1)
                ?: delay.lastOrNull()
                ?: config.worker().pollInterval()
            work.retryDelivery(lease, clock.instant().plus(retryAfter), exception.code)
            return
        }
        store.completeDeactivation(lease)
    }

    private suspend fun deliver(activation: Activation, requestedBy: String) {
        val id = activation.activationId
        val endpoint = store.route(id)?.signalDst
            ?: activation.selection?.let { configurations.get(it).manifest.backend.controlUrl }
            ?: return
        val callId = activation.request.callId
        notifier.deactivate(
            endpoint,
            PluginDeactivationMessage(
                activationId = id,
                callId = callId,
                pluginId = activation.request.pluginId,
                requestedBy = requestedBy,
                activationSecret = secrets.issue(callId, id.toString()),
            ),
        )
    }
}
