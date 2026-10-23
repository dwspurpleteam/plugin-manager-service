package ru.itmo.pluginmanager.worker.health

import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.health.PluginHealthcheck
import ru.itmo.pluginmanager.persistence.ActivationStore
import ru.itmo.pluginmanager.persistence.WorkStore
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import ru.itmo.pluginmanager.worker.WorkerProcessor
import java.time.Clock

@ApplicationScoped
class DefaultHealthcheckProcessor(
    private val store: ActivationStore,
    private val work: WorkStore,
    private val health: PluginHealthcheck,
    private val config: PluginManagerConfig,
    private val clock: Clock,
) : WorkerProcessor {
    override val kind = WorkKind.HEALTHCHECK

    override suspend fun process(lease: WorkLease) {
        val activation = store.find(lease.activationId) ?: return
        if (activation.state != ActivationState.ACTIVE) return
        val route = store.route(lease.activationId)
        val healthy = route != null && health.check(route.healthcheckUrl)
        work.recordHealthcheck(
            lease,
            healthy,
            clock.instant().plus(config.health().interval()),
            config.health().failureThreshold(),
        )
    }
}
