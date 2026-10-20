package ru.itmo.pluginmanager.worker

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.activation.ActivationProcessor
import ru.itmo.pluginmanager.health.HealthcheckProcessor
import ru.itmo.pluginmanager.persistence.MaintenanceStore
import ru.itmo.pluginmanager.persistence.WorkStore
import ru.itmo.pluginmanager.support.MemoryStores
import ru.itmo.pluginmanager.support.component
import ru.itmo.pluginmanager.support.policy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class BackgroundContractTest {
    /**
     * Тест проверяет лимит захвата работы и отдельную очистку без удаления активаций.
     * Ожидаемый результат: захват ограничен настройкой, процессоры и обе операции очистки вызываются.
     */
    @Test
    fun `dispatch respects concurrency and executes maintenance ports`() = runBlocking {
        val memory = MemoryStores()
        val processed = AtomicInteger()
        var history = false
        var routes = false
        val work = object : WorkStore by memory {
            override suspend fun claim(kind: WorkKind, limit: Int, leaseDuration: Duration): List<WorkLease> {
                assertTrue(limit <= 2)
                return List(limit) { memory.lease.copy(kind = kind) }
            }
        }
        val activation = object : ActivationProcessor {
            override suspend fun process(lease: WorkLease) {
                delay(10)
                processed.incrementAndGet()
            }
        }
        val health = object : HealthcheckProcessor {
            override suspend fun process(lease: WorkLease) {
                processed.incrementAndGet()
            }
        }
        val maintenance = object : MaintenanceStore {
            override suspend fun deleteHistoryBefore(cutoff: Instant, limit: Int): Int {
                history = true
                return 0
            }
            override suspend fun deleteInactiveRoutes(limit: Int): Int {
                routes = true
                return 0
            }
        }
        val jobs: BackgroundJobs = component(
            "worker.DefaultBackgroundJobs",
            work,
            activation,
            health,
            maintenance,
            policy(mapOf("plugin-manager.activation.concurrency" to "2", "plugin-manager.health.concurrency" to "2")),
            Clock.systemUTC(),
        )
        jobs.activateDue()
        jobs.checkDue()
        jobs.cleanUp()
        assertEquals(4, processed.get())
        assertTrue(history)
        assertTrue(routes)
    }
}
