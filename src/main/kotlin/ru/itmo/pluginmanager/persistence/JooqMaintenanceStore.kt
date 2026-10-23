package ru.itmo.pluginmanager.persistence

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.reactive.awaitSingle
import ru.itmo.pluginmanager.database.JooqTransaction
import java.time.Instant
import java.time.ZoneOffset

@ApplicationScoped
class JooqMaintenanceStore(private val transaction: JooqTransaction) : MaintenanceStore {
    override suspend fun deleteHistoryBefore(cutoff: Instant, limit: Int): Int {
        require(limit > 0)
        return transaction.transaction { dsl ->
            val locked = dsl.resultQuery("SELECT pg_try_advisory_xact_lock($HISTORY_CLEANUP_ADVISORY_LOCK_KEY)")
                .awaitSingle()
                .get(0, Boolean::class.java)
            if (!locked) return@transaction 0

            dsl.query(
                """
            DELETE FROM activation_state_history WHERE history_id IN (
                SELECT h.history_id FROM activation_state_history h
                JOIN plugin_activation a ON a.activation_id = h.activation_id
                WHERE h.changed_at < ? AND h.revision < a.state_revision
                ORDER BY h.changed_at, h.history_id LIMIT ?
            )
                """.trimIndent(),
                cutoff.atOffset(ZoneOffset.UTC),
                limit,
            ).awaitSingle()
        }
    }

    override suspend fun deleteInactiveRoutes(limit: Int): Int {
        require(limit > 0)
        return transaction.transaction { dsl ->
            dsl.query(
                """
                WITH inactive AS (
                    SELECT a.activation_id FROM plugin_activation a
                    JOIN activation_route r ON r.activation_id = a.activation_id
                    WHERE a.state IN ('STOPPED', 'FAILED', 'SUSPENDED')
                    ORDER BY a.activation_id LIMIT ? FOR UPDATE OF a SKIP LOCKED
                )
                DELETE FROM activation_route r USING inactive a WHERE r.activation_id = a.activation_id
                """.trimIndent(),
                limit,
            ).awaitSingle()
        }
    }
}
