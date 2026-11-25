package ru.itmo.pluginmanager.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.database.PostgresTestResource
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.worker.WorkKind
import java.time.Duration
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SingleActivationTest {
    @Inject
    lateinit var transaction: JooqTransaction

    @Inject
    lateinit var mapper: ObjectMapper

    private fun storage(): ActivationStore = JooqActivationStore(transaction, mapper)

    private fun work(): WorkStore = JooqWorkStore(transaction)

    private fun request() = ActivationRequest(UUID.randomUUID().toString(), "plugin", "user", "key")

    private suspend fun reset() = transaction.transaction { dsl ->
        dsl.query("TRUNCATE activation_work, activation_state_history, activation_route, plugin_activation")
            .awaitFirstOrNull()
    }

    @Test
    fun `concurrent users share one activation per plugin and call`() = runBlocking {
        reset()
        val store = storage()
        val input = request()
        val registrations = coroutineScope {
            (1..16).map { user ->
                async {
                    store.register(
                        input.copy(requestedBy = "user-$user", idempotencyKey = "key-$user", pluginVersion = "$user"),
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, registrations.map { it.activationId }.distinct().size)
        assertEquals(1, registrations.count { it.created })
        assertNotEquals(
            registrations.first().activationId,
            store.register(input.copy(callId = "another-call")).activationId,
        )
        assertNotEquals(
            registrations.first().activationId,
            store.register(input.copy(pluginId = "another-plugin", idempotencyKey = "another-key")).activationId,
        )
        val lease = work().claim(WorkKind.ACTIVATE, 10, Duration.ofSeconds(30))
            .single { it.activationId == registrations.first().activationId }
        store.fail(lease, ErrorCode.HEALTHCHECK_FAILED)
        val retry = store.register(input.copy(idempotencyKey = "retry"))
        assertNotEquals(registrations.first().activationId, retry.activationId)
        transaction.transaction { dsl ->
            dsl.query("UPDATE plugin_activation SET state = 'STOPPED' WHERE activation_id = ?", retry.activationId)
                .awaitSingle()
        }
        assertNotEquals(retry.activationId, store.register(input.copy(idempotencyKey = "restart")).activationId)
    }

    @Test
    fun `registration returns the activation if it fails after the insert conflict`() = runBlocking {
        reset()
        val store = storage()
        val input = request().copy(callId = "race-call")
        val first = store.register(input)
        transaction.transaction { dsl ->
            dsl.query(
                """
                CREATE FUNCTION finish_test_activation() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                    UPDATE plugin_activation SET state = 'FAILED', failure_code = 'HEALTHCHECK_FAILED'
                    WHERE call_id = 'race-call' AND state = 'ACTIVATING';
                    RETURN NULL;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            ).awaitFirstOrNull()
            dsl.query(
                "CREATE TRIGGER finish_test_activation AFTER INSERT ON plugin_activation " +
                    "FOR EACH STATEMENT EXECUTE FUNCTION finish_test_activation()",
            ).awaitFirstOrNull()
        }
        try {
            val result = store.register(input.copy(requestedBy = "another-user", idempotencyKey = "another-key"))
            assertEquals(first.activationId, result.activationId)
            assertEquals(false, result.created)
            assertEquals(ActivationState.FAILED, store.find(first.activationId)!!.state)
        } finally {
            transaction.transaction { dsl ->
                dsl.query("DROP TRIGGER finish_test_activation ON plugin_activation").awaitFirstOrNull()
                dsl.query("DROP FUNCTION finish_test_activation()").awaitFirstOrNull()
            }
        }
    }
}
