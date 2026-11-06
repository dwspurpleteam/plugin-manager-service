package ru.itmo.pluginmanager.persistence

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.database.PostgresTestResource
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.worker.WorkKind
import java.time.Duration
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StorageContractTest {
    @Inject
    lateinit var transaction: JooqTransaction

    private fun storage(): ActivationStore = JooqActivationStore(transaction)

    private fun work(): WorkStore = JooqWorkStore(transaction)

    private fun maintenance(): MaintenanceStore = JooqMaintenanceStore(transaction)

    private fun request() = ActivationRequest(UUID.randomUUID().toString(), "plugin", "user", "key")

    private suspend fun reset() = transaction.transaction { dsl ->
        dsl.query("TRUNCATE activation_work, activation_state_history, activation_route, plugin_activation")
            .awaitFirstOrNull()
    }

    /**
     * Тест проверяет конкурентную регистрацию и сравнение исходного запроса.
     * Ожидаемый результат: один ID, одна история и работа; изменённый запрос даёт конфликт.
     */
    @Test
    fun `concurrent idempotency is enforced by PostgreSQL`() = runBlocking {
        reset()
        val store = storage()
        val input = request()
        val registrations = coroutineScope { (1..16).map { async { store.register(input) } }.awaitAll() }
        assertEquals(1, registrations.map { it.activationId }.distinct().size)
        assertEquals(1, registrations.count { it.created })
        assertEquals(ActivationState.ACTIVATING, store.find(registrations.first().activationId)!!.state)
        val conflict = assertThrows(PluginManagerException::class.java) {
            runBlocking { store.register(input.copy(requestedBy = "different")) }
        }
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code)
        for (changed in listOf(
            input.copy(pluginId = "other"),
            input.copy(pluginVersion = "1.0.0"),
            input.copy(pluginRegistryId = "registry"),
        )) {
            assertEquals(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                assertThrows(PluginManagerException::class.java) {
                    runBlocking { store.register(changed) }
                }.code,
            )
        }
        assertNotEquals(
            registrations.first().activationId,
            store.register(input.copy(idempotencyKey = "new")).activationId,
        )
        val counts = transaction.transaction { dsl ->
            dsl.resultQuery(
                "SELECT (SELECT count(*) FROM activation_state_history), (SELECT count(*) FROM activation_work)",
            )
                .awaitSingle()
        }
        assertEquals(2L, counts.get(0))
        assertEquals(2L, counts.get(1))
    }

    /**
     * Тест проверяет восстановление просроченного lease после перезапуска обработчика.
     * Ожидаемый результат: новый токен захватывает работу, старый не может записать результат.
     */
    @Test
    fun `expired work is reclaimed and stale result is fenced`() = runBlocking {
        reset()
        val id = storage().register(request()).activationId
        val first = work().claim(WorkKind.ACTIVATE, 1, Duration.ofSeconds(30)).single()
        assertEquals(id, first.activationId)
        assertEquals(emptyList<Any>(), work().claim(WorkKind.ACTIVATE, 1, Duration.ofSeconds(30)))
        transaction.transaction { dsl ->
            dsl.query("UPDATE activation_work SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'").awaitSingle()
        }
        val second = work().claim(WorkKind.ACTIVATE, 1, Duration.ofSeconds(30)).single()
        assertNotEquals(first.token, second.token)
        assertEquals(2, second.attemptCount)
        val stale = assertThrows(PluginManagerException::class.java) {
            runBlocking { storage().pinSelection(first, PluginSelection("plugin", "1", "registry")) }
        }
        assertEquals(ErrorCode.LEASE_LOST, stale.code)
        storage().pinSelection(second, PluginSelection("plugin", "1", "registry"))
        assertEquals("1", storage().find(id)!!.selection!!.pluginVersion)
        assertThrows(PluginManagerException::class.java) {
            runBlocking { storage().pinSelection(second, PluginSelection("plugin", "2", "registry")) }
        }
        Unit
    }

    /**
     * Тест проверяет конкурентный захват фоновых заданий несколькими обработчиками.
     * Ожидаемый результат: каждое задание выдано ровно одному worker до истечения lease.
     */
    @Test
    fun `concurrent claims never overlap`() = runBlocking {
        reset()
        repeat(8) { storage().register(request()) }
        val claims = coroutineScope {
            (1..8).map { async { work().claim(WorkKind.ACTIVATE, 2, Duration.ofSeconds(30)) } }.awaitAll().flatten()
        }
        assertEquals(8, claims.size)
        assertEquals(8, claims.map { it.activationId }.distinct().size)
    }

    /**
     * Тест проверяет переход ACTIVE, последовательные healthcheck и атомарную историю.
     * Ожидаемый результат: успех сбрасывает ошибки; три последующие ошибки дают FAILED без новой работы.
     */
    @Test
    fun `health failures are consecutive and history matches current state`() = runBlocking {
        reset()
        val store = storage()
        val id = store.register(request()).activationId
        val lease = work().claim(WorkKind.ACTIVATE, 1, Duration.ofSeconds(30)).single()
        store.pinSelection(lease, PluginSelection("plugin", "1", "registry"))
        assertThrows(PluginManagerException::class.java) { runBlocking { store.activate(lease, Instant.EPOCH) } }
        store.saveRoute(
            lease,
            ActivationRoute(
                routeId = UUID.randomUUID(),
                activationId = id,
                signalDst = "https://example.com",
                allowedEventTypes = null,
                allowedEventGroups = null,
                healthcheckUrl = "https://example.com/health",
            ),
        )
        store.activate(lease, Instant.EPOCH)
        for (healthy in listOf(false, false, true, false, false)) {
            val probe = work().claim(WorkKind.HEALTHCHECK, 1, Duration.ofSeconds(30)).single()
            work().recordHealthcheck(probe, healthy, Instant.EPOCH, 3)
            assertEquals(ActivationState.ACTIVE, store.find(id)!!.state)
        }
        val last = work().claim(WorkKind.HEALTHCHECK, 1, Duration.ofSeconds(30)).single()
        work().recordHealthcheck(last, false, Instant.EPOCH, 3)
        assertEquals(ActivationState.FAILED, store.find(id)!!.state)
        assertEquals(3, store.find(id)!!.stateRevision)
        assertEquals(emptyList<Any>(), work().claim(WorkKind.HEALTHCHECK, 1, Duration.ofSeconds(30)))
        val history = transaction.transaction { dsl ->
            dsl.resultQuery("SELECT to_state FROM activation_state_history ORDER BY revision").asFlow().toList()
        }
        assertEquals(listOf("ACTIVATING", "ACTIVE", "FAILED"), history.map { it.get(0) })
    }

    /**
     * Тест проверяет очистку истории и маршрутов без удаления активаций.
     * Ожидаемый результат: текущая история и активный маршрут сохраняются; FAILED-маршрут удаляется.
     */
    @Test
    fun `garbage collection preserves current revision and active routes`() = runBlocking {
        reset()
        val store = storage()
        val id = store.register(request()).activationId
        val lease = work().claim(WorkKind.ACTIVATE, 1, Duration.ofSeconds(30)).single()
        store.pinSelection(lease, PluginSelection("plugin", "1", "registry"))
        store.saveRoute(
            lease,
            ActivationRoute(
                routeId = UUID.randomUUID(),
                activationId = id,
                signalDst = "https://example.com",
                allowedEventTypes = null,
                allowedEventGroups = null,
                healthcheckUrl = "https://example.com/health",
            ),
        )
        store.activate(lease, Instant.EPOCH)
        assertEquals(0, maintenance().deleteInactiveRoutes(100))
        assertNotNull(store.route(id))
        assertEquals(1, maintenance().deleteHistoryBefore(Instant.now().plusSeconds(60), 100))
        store.fail(work().claim(WorkKind.HEALTHCHECK, 1, Duration.ofSeconds(30)).single(), ErrorCode.HEALTHCHECK_FAILED)
        assertEquals(1, maintenance().deleteInactiveRoutes(100))
        assertNull(store.route(id))
        assertFalse(store.find(id) == null)
        assertEquals(1, maintenance().deleteHistoryBefore(Instant.now().plusSeconds(60), 100))
    }
}
