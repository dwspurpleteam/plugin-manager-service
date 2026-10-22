package ru.itmo.pluginmanager.worker

import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.jboss.logging.Logger
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.persistence.MaintenanceStore
import ru.itmo.pluginmanager.persistence.WorkStore
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class DefaultBackgroundJobs(
    private val work: WorkStore,
    @All processors: MutableList<WorkerProcessor>,
    private val maintenance: MaintenanceStore,
    private val config: PluginManagerConfig,
    private val clock: Clock,
) : BackgroundJobs {
    private val processors = processors.associateBy { it.kind }.also { byKind ->
        check(byKind.size == WorkKind.entries.size) { "Exactly one worker processor must be configured for each kind" }
    }
    private val activationLock = Mutex()
    private val healthLock = Mutex()
    private val cleanupLock = Mutex()
    private val logger = Logger.getLogger(javaClass)
    private val instanceId = UUID.randomUUID()

    override suspend fun activateDue() = dispatch(
        activationLock,
        WorkKind.ACTIVATE,
        config.activation().concurrency(),
    ) {
        processor(WorkKind.ACTIVATE).process(it)
    }

    override suspend fun checkDue() = dispatch(healthLock, WorkKind.HEALTHCHECK, config.health().concurrency()) {
        processor(WorkKind.HEALTHCHECK).process(it)
    }

    private fun processor(kind: WorkKind) = checkNotNull(processors[kind]) { "Worker processor is missing for $kind" }

    private suspend fun dispatch(lock: Mutex, kind: WorkKind, limit: Int, action: suspend (WorkLease) -> Unit) {
        if (!lock.tryLock()) return
        try {
            val leases = work.claim(kind, limit, config.worker().leaseDuration())
            coroutineScope {
                leases.forEachIndexed { index, lease ->
                    launch {
                        val workerId = "$instanceId:${kind.name.lowercase()}-${index + 1}"
                        logger.infof(
                            "Worker picked up task: workerId=%s, kind=%s, activationId=%s, " +
                                "leaseToken=%s, expiresAt=%s, attemptCount=%d, consecutiveHealthFailures=%d",
                            workerId,
                            lease.kind,
                            lease.activationId,
                            lease.token,
                            lease.expiresAt,
                            lease.attemptCount,
                            lease.consecutiveHealthFailures,
                        )
                        runCatching {
                            withTimeoutOrNull(
                                config.worker().leaseDuration()
                                    .minus(config.worker().pollInterval())
                                    .toMillis()
                                    .milliseconds,
                            ) {
                                action(lease)
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            logger.errorf(
                                it,
                                "Background execution failed: workerId=%s, kind=%s, activationId=%s",
                                workerId,
                                lease.kind,
                                lease.activationId,
                            )
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
