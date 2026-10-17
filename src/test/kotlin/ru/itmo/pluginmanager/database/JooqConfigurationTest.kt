package ru.itmo.pluginmanager.database

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class JooqConfigurationTest {
    @Inject
    lateinit var transaction: JooqTransaction

    @Test
    fun `executes query in suspend transaction over R2DBC`() = runBlocking {
        val value = transaction.transaction { dsl ->
            dsl.selectOne().awaitSingle().value1()
        }

        assertEquals(1, value)
    }
}
