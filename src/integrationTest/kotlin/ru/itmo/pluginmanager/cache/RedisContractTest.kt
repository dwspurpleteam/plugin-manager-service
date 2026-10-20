package ru.itmo.pluginmanager.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.client.RedisClientName
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import ru.itmo.pluginmanager.database.PostgresTestResource
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.support.component
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy

class RedisTestResource : QuarkusTestResourceLifecycleManager {
    private val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    override fun start(): Map<String, String> {
        redis.start()
        return mapOf("quarkus.redis.hosts" to "redis://${redis.host}:${redis.firstMappedPort}")
    }
    override fun stop() = redis.stop()
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(RedisTestResource::class)
class RedisContractTest {
    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    @RedisClientName("unavailable")
    lateinit var unavailable: ReactiveRedisDataSource

    /**
     * Тест проверяет хранение неизменяемого манифеста в настоящем Redis и истечение TTL.
     * Ожидаемый результат: значения разных реестров не пересекаются, запись исчезает по TTL.
     */
    @Test
    fun `redis caches separate selections and expires manifests`() = runBlocking {
        redis.flushall().awaitSuspending()
        val cache: ManifestCache = component(
            "cache.RedisManifestCache",
            redis,
            mapper,
            policy(mapOf("plugin-manager.cache.ttl" to "PT1S")),
        )
        val selection = PluginSelection("plugin", "1.0.0", "registry")
        cache.put(selection, manifest())
        assertEquals(manifest(), cache.get(selection))
        assertNull(cache.get(selection.copy(registryId = "different")))
        delay(1200)
        assertNull(cache.get(selection))
    }

    /**
     * Тест проверяет повреждённые записи Redis.
     * Ожидаемый результат: чтение повреждённого JSON ведёт себя как промах.
     */
    @Test
    fun `corrupted Redis value is a cache miss`() = runBlocking {
        redis.flushall().awaitSuspending()
        val cache: ManifestCache = component("cache.RedisManifestCache", redis, mapper, policy())
        val selection = PluginSelection("plugin", "1.0.0", "registry")
        cache.put(selection, manifest())
        val keys = redis.key(String::class.java).keys("manifest:v1:*").awaitSuspending()
        redis.value(String::class.java).set(keys.single(), "{broken").awaitSuspending()
        assertNull(cache.get(selection))
    }

    /**
     * Тест проверяет отсутствие соединения с Redis.
     * Ожидаемый результат: чтение даёт промах, запись не срывает успешную операцию реестра.
     */
    @Test
    fun `Redis outage is best effort and bounded`() = runBlocking {
        val cache: ManifestCache = component("cache.RedisManifestCache", unavailable, mapper, policy())
        val selection = PluginSelection("plugin", "1.0.0", "registry")
        assertNull(cache.get(selection))
        cache.put(selection, manifest())
    }
}
