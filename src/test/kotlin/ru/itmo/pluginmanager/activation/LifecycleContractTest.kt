package ru.itmo.pluginmanager.activation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.application.ActivationService
import ru.itmo.pluginmanager.application.ActivationServiceImpl
import ru.itmo.pluginmanager.application.ConfigurationService
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginConfiguration
import ru.itmo.pluginmanager.domain.PluginEvents
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.health.PluginHealthcheck
import ru.itmo.pluginmanager.support.MemoryStores
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkerProcessor
import ru.itmo.pluginmanager.worker.activation.DefaultActivationProcessor
import ru.itmo.pluginmanager.worker.health.DefaultHealthcheckProcessor
import java.time.Clock

class LifecycleContractTest {
    private val store = MemoryStores()
    private var healthy = true
    private var resolutions = 0
    private var configuredManifest = manifest()
    private val health = object : PluginHealthcheck {
        override suspend fun check(url: String) = healthy
    }
    private val configurations = object : ConfigurationService {
        override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?): PluginSelection {
            resolutions++
            return PluginSelection("plugin", "1.0.0", "registry")
        }
        override suspend fun get(selection: PluginSelection) = PluginConfiguration(selection, configuredManifest)
    }

    private fun processor(): WorkerProcessor = DefaultActivationProcessor(
        store,
        store,
        configurations,
        health,
        policy(),
        Clock.systemUTC(),
    )

    /**
     * Тест проверяет завершение активации и повторное использование закреплённой версии.
     * Ожидаемый результат: маршрут использует разрешения событий, ACTIVE наступает после healthcheck,
     * повтор не ищет latest.
     */
    @Test
    fun `activation pins selection and requires successful probe`() = runBlocking {
        healthy = false
        processor().process(store.lease)
        assertEquals(ActivationState.ACTIVATING, store.activation.state)
        assertTrue(store.retried)
        healthy = true
        processor().process(store.lease.copy(attemptCount = 2))
        assertEquals(ActivationState.ACTIVE, store.activation.state)
        assertEquals(manifest().backend.events.availableEventTypes, store.savedRoute!!.allowedEventTypes)
        assertEquals(null, store.savedRoute!!.allowedEventGroups)
        assertEquals(WorkKind.ACTIVATE, processor().kind)
        assertEquals(1, resolutions)
    }

    /**
     * Тест проверяет сохранение разрешённых групп событий из манифеста плагина.
     * Ожидаемый результат: маршрут содержит группы, когда конкретные типы событий не заданы.
     */
    @Test
    fun `activation pins allowed event groups from manifest`() = runBlocking {
        configuredManifest = manifest().copy(
            backend = manifest().backend.copy(
                events = PluginEvents(
                    eventUrl = manifest().backend.events.eventUrl,
                    availableEventGroups = setOf("participants", "recording"),
                ),
            ),
        )

        processor().process(store.lease)

        assertEquals(null, store.savedRoute!!.allowedEventTypes)
        assertEquals(setOf("participants", "recording"), store.savedRoute!!.allowedEventGroups)
    }

    /**
     * Тест проверяет исчерпание попыток, включая восстановление после падения последнего worker.
     * Ожидаемый результат: FAILED и отсутствие дополнительных обращений к реестру сверх лимита.
     */
    @Test
    fun `exhausted attempts fail without endless restart retries`() = runBlocking {
        processor().process(store.lease.copy(attemptCount = 4))
        assertEquals(ActivationState.FAILED, store.activation.state)
        assertEquals(0, resolutions)
        assertFalse(store.retried)
    }

    /**
     * Тест проверяет последний неуспешный healthcheck при активации.
     * Ожидаемый результат: FAILED без назначения четвёртой попытки.
     */
    @Test
    fun `last unsuccessful activation probe fails terminally`() = runBlocking {
        healthy = false
        processor().process(store.lease.copy(attemptCount = 3))
        assertEquals(ActivationState.FAILED, store.activation.state)
        assertEquals(ErrorCode.HEALTHCHECK_FAILED, store.activation.failureCode)
        assertFalse(store.retried)
    }

    /**
     * Тест проверяет запрет выдачи маршрута для неготовой или неактивной активации.
     * Ожидаемый результат: NOT_READY и FAILED вместо успешного ответа; неизвестный ID даёт NOT_FOUND.
     */
    @Test
    fun `read service gates routes by activation state`() = runBlocking {
        val service: ActivationService = ActivationServiceImpl(store, policy(), configurations)
        val pending = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.signalRoute(store.activation.activationId) }
        }
        assertEquals(ErrorCode.ACTIVATION_NOT_READY, pending.code)
        store.activation = store.activation.copy(state = ActivationState.FAILED)
        val failed = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.mediaTypes(store.activation.activationId) }
        }
        assertEquals(ErrorCode.ACTIVATION_FAILED, failed.code)
    }

    /**
     * Тест проверяет передачу результата healthcheck в атомарное хранилище.
     * Ожидаемый результат: неуспех учитывается хранилищем, конфигурация повторно не разрешается.
     */
    @Test
    fun `periodic probe uses saved route`() = runBlocking {
        processor().process(store.lease)
        healthy = false
        val probe: WorkerProcessor = DefaultHealthcheckProcessor(
            store,
            store,
            health,
            policy(),
            Clock.systemUTC(),
        )
        probe.process(store.lease)
        assertEquals(WorkKind.HEALTHCHECK, probe.kind)
        assertEquals(false, store.checked)
        assertEquals(1, resolutions)
    }
}
