package ru.itmo.pluginmanager.database

import jakarta.enterprise.context.ApplicationScoped
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine

@ApplicationScoped
class JooqTransaction(
    private val dslContext: DSLContext
) {
    suspend fun <T> transaction(block: suspend (DSLContext) -> T): T =
        dslContext.transactionCoroutine { configuration ->
            block(configuration.dsl())
        }
}
