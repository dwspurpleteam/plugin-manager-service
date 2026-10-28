package ru.itmo.pluginmanager.persistence

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_WORK
import ru.itmo.pluginmanager.jooq.generated.tables.records.ActivationWorkRecord
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationState as DatabaseActivationState
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationWorkKind as DatabaseActivationWorkKind

@ApplicationScoped
class JooqWorkStore(private val transaction: JooqTransaction) : WorkStore {
    override suspend fun claim(kind: WorkKind, limit: Int, leaseDuration: Duration): List<WorkLease> {
        require(limit > 0 && !leaseDuration.isNegative && !leaseDuration.isZero)
        return transaction.transaction { dsl ->
            dsl.resultQuery(
                """
                    WITH picked AS (
                        SELECT w.activation_id 
                        FROM activation_work w
                        JOIN plugin_activation a ON a.activation_id = w.activation_id
                        WHERE w.kind = ? 
                            AND a.state = ? 
                            AND w.next_run_at <= clock_timestamp()
                            AND (w.lease_until IS NULL OR w.lease_until <= clock_timestamp())
                        ORDER BY w.next_run_at, w.activation_id LIMIT ? FOR UPDATE OF w SKIP LOCKED
                    )
                    UPDATE activation_work w 
                    SET lease_token = gen_random_uuid(),
                        lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        attempt_count = w.attempt_count + 1
                    FROM picked p WHERE w.activation_id = p.activation_id RETURNING w.*
                """.trimIndent(),
                DatabaseActivationWorkKind.valueOf(kind.name),
                if (kind == WorkKind.ACTIVATE) DatabaseActivationState.ACTIVATING else DatabaseActivationState.ACTIVE,
                limit,
                leaseDuration.toMillis(),
            ).asFlow().toList().map { row ->
                val record = row.into(ActivationWorkRecord::class.java)
                WorkLease(
                    record.activationId,
                    record.leaseToken,
                    kind,
                    record.leaseUntil.toInstant(),
                    record.attemptCount,
                    record.consecutiveHealthFailures,
                )
            }
        }
    }

    override suspend fun retryActivation(lease: WorkLease, nextRunAt: Instant, reason: ErrorCode) {
        transaction.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            if (activation.state != DatabaseActivationState.ACTIVATING ||
                lease.kind != WorkKind.ACTIVATE
            ) {
                storageError()
            }
            dsl.update(ACTIVATION_WORK).set(ACTIVATION_WORK.NEXT_RUN_AT, nextRunAt.atOffset(ZoneOffset.UTC))
                .set(ACTIVATION_WORK.LAST_ERROR_CODE, reason.name)
                .setNull(ACTIVATION_WORK.LEASE_TOKEN).setNull(ACTIVATION_WORK.LEASE_UNTIL)
                .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
        }
    }

    override suspend fun recordHealthcheck(
        lease: WorkLease,
        healthy: Boolean,
        nextRunAt: Instant,
        failureThreshold: Int,
    ) {
        require(failureThreshold > 0)
        transaction.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            if (activation.state != DatabaseActivationState.ACTIVE || lease.kind != WorkKind.HEALTHCHECK) storageError()
            val work = dsl.selectFrom(
                ACTIVATION_WORK,
            ).where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
            val failures = if (healthy) 0 else work.consecutiveHealthFailures + 1
            if (failures >= failureThreshold) {
                dsl.changeState(activation, ActivationState.FAILED, ErrorCode.HEALTHCHECK_FAILED)
                dsl.deleteFrom(
                    ACTIVATION_WORK,
                ).where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
            } else {
                dsl.update(ACTIVATION_WORK).set(ACTIVATION_WORK.NEXT_RUN_AT, nextRunAt.atOffset(ZoneOffset.UTC))
                    .set(ACTIVATION_WORK.CONSECUTIVE_HEALTH_FAILURES, failures)
                    .set(ACTIVATION_WORK.LAST_ERROR_CODE, if (healthy) null else ErrorCode.HEALTHCHECK_FAILED.name)
                    .set(ACTIVATION_WORK.ATTEMPT_COUNT, 0)
                    .setNull(ACTIVATION_WORK.LEASE_TOKEN).setNull(ACTIVATION_WORK.LEASE_UNTIL)
                    .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
            }
        }
    }
}
