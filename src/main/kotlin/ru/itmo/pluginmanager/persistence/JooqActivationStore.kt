package ru.itmo.pluginmanager.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.jooq.JSONB
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.domain.RegistrationResult
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_ROUTE
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_STATE_HISTORY
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_WORK
import ru.itmo.pluginmanager.jooq.generated.Tables.PLUGIN_ACTIVATION
import ru.itmo.pluginmanager.worker.WorkKind
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationState as DatabaseActivationState
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationWorkKind as DatabaseActivationWorkKind

@ApplicationScoped
class JooqActivationStore(private val tx: JooqTransaction, private val mapper: ObjectMapper) : ActivationStore {
    private val controlUrl = field(name("control_url"), String::class.java)
    private val controlUrlUnknown = field(name("control_url_unknown"), Boolean::class.javaObjectType)

    override suspend fun register(request: ActivationRequest): RegistrationResult = tx.transaction { dsl ->
        val id = UUID.randomUUID()
        val inserted = dsl.insertInto(PLUGIN_ACTIVATION)
            .set(PLUGIN_ACTIVATION.ACTIVATION_ID, id)
            .set(PLUGIN_ACTIVATION.CALL_ID, request.callId)
            .set(PLUGIN_ACTIVATION.PLUGIN_ID, request.pluginId)
            .set(PLUGIN_ACTIVATION.REQUESTED_BY, request.requestedBy)
            .set(PLUGIN_ACTIVATION.IDEMPOTENCY_KEY, request.idempotencyKey)
            .set(PLUGIN_ACTIVATION.REQUESTED_VERSION, request.pluginVersion)
            .set(PLUGIN_ACTIVATION.REQUESTED_REGISTRY_ID, request.pluginRegistryId)
            .set(
                PLUGIN_ACTIVATION.INITIAL_CONTEXT,
                request.initialContext?.let { context ->
                    JSONB.valueOf(mapper.writeValueAsString(context))
                },
            )
            .set(PLUGIN_ACTIVATION.STATE, DatabaseActivationState.ACTIVATING)
            .onConflict(PLUGIN_ACTIVATION.CALL_ID, PLUGIN_ACTIVATION.IDEMPOTENCY_KEY).doNothing().awaitSingle()
        if (inserted == 0) {
            val old = dsl.selectFrom(PLUGIN_ACTIVATION).where(PLUGIN_ACTIVATION.CALL_ID.eq(request.callId))
                .and(PLUGIN_ACTIVATION.IDEMPOTENCY_KEY.eq(request.idempotencyKey)).awaitSingle().toDomain(mapper)
            if (old.request != request) storageError(ErrorCode.IDEMPOTENCY_CONFLICT)
            RegistrationResult(old.activationId, false)
        } else {
            dsl.insertInto(ACTIVATION_STATE_HISTORY)
                .set(ACTIVATION_STATE_HISTORY.HISTORY_ID, UUID.randomUUID())
                .set(ACTIVATION_STATE_HISTORY.ACTIVATION_ID, id)
                .set(ACTIVATION_STATE_HISTORY.REVISION, 1L)
                .set(ACTIVATION_STATE_HISTORY.TO_STATE, DatabaseActivationState.ACTIVATING).awaitSingle()
            dsl.query(
                "INSERT INTO activation_work (activation_id, kind, requested_by) VALUES (?, 'ACTIVATE', ?)",
                id,
                request.requestedBy,
            ).awaitSingle()
            RegistrationResult(id, true)
        }
    }

    override suspend fun beginDeactivation(activationId: UUID, requestedBy: String) = tx.transaction { dsl ->
        // Workers lock the work row before locking the activation.
        val work = dsl.select(ACTIVATION_WORK.LEASE_UNTIL, ACTIVATION_WORK.KIND.cast(String::class.java))
            .from(ACTIVATION_WORK).where(ACTIVATION_WORK.ACTIVATION_ID.eq(activationId))
            .forUpdate().awaitFirstOrNull()
        val record = dsl.selectFrom(PLUGIN_ACTIVATION).where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId))
            .forUpdate().awaitFirstOrNull() ?: storageError(ErrorCode.ACTIVATION_NOT_FOUND)
        if (record.state == DatabaseActivationState.STOPPED) {
            return@transaction record.toDomain(mapper)
        }
        if (dsl.select(controlUrlUnknown).from(PLUGIN_ACTIVATION)
                .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId)).awaitSingle().value1() == true
        ) {
            throw missingControlUrl()
        }
        if (record.state == DatabaseActivationState.STOPPING &&
            dsl.selectOne().from(ACTIVATION_WORK).where(ACTIVATION_WORK.ACTIVATION_ID.eq(activationId))
                .awaitFirstOrNull() != null
        ) {
            return@transaction record.toDomain(mapper)
        }

        if (record.state != DatabaseActivationState.STOPPING) {
            dsl.changeState(record, ActivationState.STOPPING, null)
        }
        // A claimed activation may still be delivering its control message.
        val activationLeaseUntil = work?.takeIf { it.value2() == "ACTIVATE" }?.value1()
        dsl.query(
            """
                INSERT INTO activation_work (activation_id, kind, requested_by, next_run_at)
                VALUES (?, 'DEACTIVATE', ?, COALESCE(?::timestamptz, clock_timestamp()))
                ON CONFLICT (activation_id) DO UPDATE
                SET kind = EXCLUDED.kind, requested_by = EXCLUDED.requested_by,
                    next_run_at = EXCLUDED.next_run_at, attempt_count = 0, consecutive_health_failures = 0,
                    lease_token = NULL, lease_until = NULL, last_error_code = NULL
            """.trimIndent(),
            activationId,
            requestedBy,
            activationLeaseUntil,
        ).awaitSingle()
        dsl.selectFrom(PLUGIN_ACTIVATION)
            .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId)).awaitSingle().toDomain(mapper)
    }

    override suspend fun completeDeactivation(lease: WorkLease) {
        check(lease.kind == WorkKind.DEACTIVATE)
        tx.transaction { dsl ->
            val record = dsl.lockActivation(lease)
            dsl.changeState(record, ActivationState.STOPPED, null)
            dsl.deleteFrom(ACTIVATION_ROUTE)
                .where(ACTIVATION_ROUTE.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
            dsl.deleteFrom(ACTIVATION_WORK)
                .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId)).awaitSingle()
        }
    }

    override suspend fun find(activationId: UUID) = tx.transaction { dsl ->
        dsl.selectFrom(PLUGIN_ACTIVATION)
            .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId))
            .awaitFirstOrNull()?.toDomain(mapper)
    }

    override suspend fun findActiveByCall(callId: String) = tx.transaction { dsl ->
        dsl.selectFrom(PLUGIN_ACTIVATION)
            .where(PLUGIN_ACTIVATION.CALL_ID.eq(callId))
            .and(PLUGIN_ACTIVATION.STATE.eq(DatabaseActivationState.ACTIVE))
            .asFlow()
            .toList()
            .map { it.toDomain(mapper) }
    }

    override suspend fun route(activationId: UUID) = tx.transaction { it.readRoute(activationId) }

    override suspend fun controlUrl(activationId: UUID): String? = tx.transaction { dsl ->
        val endpoint = dsl.select(controlUrl, controlUrlUnknown).from(PLUGIN_ACTIVATION)
            .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId)).awaitSingle()
        if (endpoint.value2() == true) throw missingControlUrl()
        endpoint.value1()
    }

    override suspend fun pinSelection(lease: WorkLease, selection: PluginSelection) {
        tx.transaction { dsl ->
            val activation = dsl.lockActivation(lease).toDomain(mapper)
            if (activation.state != ActivationState.ACTIVATING || selection.pluginId != activation.request.pluginId) {
                storageError()
            }
            if (activation.selection != null && activation.selection != selection) storageError()
            dsl.update(PLUGIN_ACTIVATION)
                .set(PLUGIN_ACTIVATION.RESOLVED_VERSION, selection.pluginVersion)
                .set(PLUGIN_ACTIVATION.RESOLVED_REGISTRY_ID, selection.registryId)
                .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(lease.activationId))
                .awaitSingle()
        }
    }

    override suspend fun saveRoute(lease: WorkLease, route: ActivationRoute) {
        tx.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            if (activation.state != DatabaseActivationState.ACTIVATING || activation.resolvedVersion == null ||
                route.activationId != lease.activationId
            ) {
                storageError()
            }
            dsl.insertInto(ACTIVATION_ROUTE).set(ACTIVATION_ROUTE.ROUTE_ID, route.routeId)
                .set(ACTIVATION_ROUTE.ACTIVATION_ID, lease.activationId)
                .set(ACTIVATION_ROUTE.SIGNAL_DST, route.signalDst)
                .set(ACTIVATION_ROUTE.ALLOWED_EVENT_TYPES, route.allowedEventTypes?.sorted()?.toTypedArray())
                .set(ACTIVATION_ROUTE.ALLOWED_EVENT_GROUPS, route.allowedEventGroups?.sorted()?.toTypedArray())
                .set(ACTIVATION_ROUTE.HEALTHCHECK_URL, route.healthcheckUrl)
                .onConflict(ACTIVATION_ROUTE.ACTIVATION_ID).doNothing().awaitSingle()
            dsl.update(PLUGIN_ACTIVATION)
                .set(
                    controlUrl,
                    dsl.select(ACTIVATION_ROUTE.SIGNAL_DST).from(ACTIVATION_ROUTE)
                        .where(ACTIVATION_ROUTE.ACTIVATION_ID.eq(lease.activationId)),
                )
                .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(lease.activationId))
                .and(controlUrl.isNull)
                .awaitSingle()
        }
    }

    override suspend fun activate(lease: WorkLease, nextHealthcheckAt: Instant) {
        tx.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            if (activation.state != DatabaseActivationState.ACTIVATING ||
                activation.resolvedVersion == null || dsl.readRoute(
                    lease.activationId,
                ) == null
            ) {
                storageError()
            }
            dsl.changeState(activation, ActivationState.ACTIVE, null)
            dsl.update(ACTIVATION_WORK)
                .set(ACTIVATION_WORK.KIND, DatabaseActivationWorkKind.HEALTHCHECK)
                .set(ACTIVATION_WORK.NEXT_RUN_AT, nextHealthcheckAt.atOffset(ZoneOffset.UTC))
                .set(ACTIVATION_WORK.ATTEMPT_COUNT, 0)
                .set(ACTIVATION_WORK.CONSECUTIVE_HEALTH_FAILURES, 0)
                .setNull(ACTIVATION_WORK.LEASE_TOKEN)
                .setNull(ACTIVATION_WORK.LEASE_UNTIL)
                .setNull(ACTIVATION_WORK.LAST_ERROR_CODE)
                .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId))
                .awaitSingle()
        }
    }

    override suspend fun fail(lease: WorkLease, reason: ErrorCode) {
        tx.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            dsl.changeState(activation, ActivationState.FAILED, reason)
            dsl.deleteFrom(ACTIVATION_WORK)
                .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId))
                .awaitSingle()
        }
    }
}

private fun missingControlUrl() = PluginManagerException(
    ErrorCode.PLUGIN_DEACTIVATION_UNAVAILABLE,
    "Original plugin control URL is unavailable",
)
