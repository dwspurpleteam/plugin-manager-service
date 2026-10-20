package ru.itmo.pluginmanager.worker

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.jboss.logging.Logger
import ru.itmo.pluginmanager.activation.ActivationProcessor
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.health.HealthcheckProcessor
import ru.itmo.pluginmanager.persistence.MaintenanceStore
import ru.itmo.pluginmanager.persistence.WorkStore
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class DefaultBackgroundJobs(
    private val work: WorkStore,
    private val activation: ActivationProcessor,
    private val health: HealthcheckProcessor,
    private val maintenance: MaintenanceStore,
    private val config: PluginManagerConfig,
    private val clock: Clock,
) : BackgroundJobs {
    private val activationLock = Mutex()
    private val healthLock = Mutex()
    private val cleanupLock = Mutex()

    override suspend fun activateDue() = dispatch(
        activationLock,
        WorkKind.ACTIVATE,
        config.activation().concurrency(),
    ) {
        activation.process(it)
    }

    override suspend fun checkDue() = dispatch(healthLock, WorkKind.HEALTHCHECK, config.health().concurrency()) {
        health.process(it)
    }

    private suspend fun dispatch(lock: Mutex, kind: WorkKind, limit: Int, action: suspend (WorkLease) -> Unit) {
        if (!lock.tryLock()) return
        try {
            val leases = work.claim(kind, limit, config.worker().leaseDuration())
            coroutineScope {
                leases.forEach { lease ->
                    launch {
                        runCatching {
                            withTimeoutOrNull(
                                config.worker().leaseDuration().minus(config.worker().pollInterval()).toMillis().milliseconds,
                            ) {
                                action(lease)
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            Logger.getLogger(
                                javaClass,
                            ).errorf(it, "Background execution failed: %s", lease.activationId)
                        }
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    override suspend fun cleanUp() {
        if (!cleanupLock.tryLock()) return
        try {
            maintenance.deleteHistoryBefore(
                clock.instant().minus(config.cleanup().historyRetention()),
                config.cleanup().batchSize(),
            )
            if (config.cleanup().inactiveRoutesEnabled()) maintenance.deleteInactiveRoutes(config.cleanup().batchSize())
        } finally {
            cleanupLock.unlock()
        }
    }
}
