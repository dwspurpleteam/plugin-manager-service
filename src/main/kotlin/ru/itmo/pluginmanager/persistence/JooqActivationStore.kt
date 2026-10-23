package ru.itmo.pluginmanager.persistence

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.domain.RegistrationResult
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_ROUTE
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_STATE_HISTORY
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_WORK
import ru.itmo.pluginmanager.jooq.generated.Tables.PLUGIN_ACTIVATION
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationState as DatabaseActivationState
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationWorkKind as DatabaseActivationWorkKind
import ru.itmo.pluginmanager.jooq.generated.enums.MediaType as DatabaseMediaType

@ApplicationScoped
class JooqActivationStore(private val transaction: JooqTransaction) : ActivationStore {
    override suspend fun register(request: ActivationRequest): RegistrationResult = transaction.transaction { dsl ->
        val id = UUID.randomUUID()
        val inserted = dsl.insertInto(PLUGIN_ACTIVATION)
            .set(PLUGIN_ACTIVATION.ACTIVATION_ID, id)
            .set(PLUGIN_ACTIVATION.CALL_ID, request.callId)
            .set(PLUGIN_ACTIVATION.PLUGIN_ID, request.pluginId)
            .set(PLUGIN_ACTIVATION.REQUESTED_BY, request.requestedBy)
            .set(PLUGIN_ACTIVATION.IDEMPOTENCY_KEY, request.idempotencyKey)
            .set(PLUGIN_ACTIVATION.REQUESTED_VERSION, request.pluginVersion)
            .set(PLUGIN_ACTIVATION.REQUESTED_REGISTRY_ID, request.pluginRegistryId)
            .set(PLUGIN_ACTIVATION.STATE, DatabaseActivationState.ACTIVATING)
            .onConflict(PLUGIN_ACTIVATION.CALL_ID, PLUGIN_ACTIVATION.IDEMPOTENCY_KEY).doNothing().awaitSingle()
        if (inserted == 0) {
            val old = dsl.selectFrom(PLUGIN_ACTIVATION).where(PLUGIN_ACTIVATION.CALL_ID.eq(request.callId))
                .and(PLUGIN_ACTIVATION.IDEMPOTENCY_KEY.eq(request.idempotencyKey)).awaitSingle().toDomain()
            if (old.request != request) storageError(ErrorCode.IDEMPOTENCY_CONFLICT)
            RegistrationResult(old.activationId, false)
        } else {
            dsl.insertInto(ACTIVATION_STATE_HISTORY)
                .set(ACTIVATION_STATE_HISTORY.HISTORY_ID, UUID.randomUUID())
                .set(ACTIVATION_STATE_HISTORY.ACTIVATION_ID, id)
                .set(ACTIVATION_STATE_HISTORY.REVISION, 1L)
                .set(ACTIVATION_STATE_HISTORY.TO_STATE, DatabaseActivationState.ACTIVATING).awaitSingle()
            dsl.insertInto(ACTIVATION_WORK)
                .set(ACTIVATION_WORK.ACTIVATION_ID, id)
                .set(ACTIVATION_WORK.KIND, DatabaseActivationWorkKind.ACTIVATE).awaitSingle()
            RegistrationResult(id, true)
        }
    }

    override suspend fun find(activationId: UUID) = transaction.transaction { dsl ->
        dsl.selectFrom(PLUGIN_ACTIVATION)
            .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(activationId))
            .awaitFirstOrNull()?.toDomain()
    }

    override suspend fun route(activationId: UUID) = transaction.transaction { it.readRoute(activationId) }

    override suspend fun pinSelection(lease: WorkLease, selection: PluginSelection) {
        transaction.transaction { dsl ->
            val activation = dsl.lockActivation(lease).toDomain()
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
        transaction.transaction { dsl ->
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
                .set(
                    ACTIVATION_ROUTE.MEDIA_TYPES,
                    route.mediaTypes.map { DatabaseMediaType.valueOf(it.name) }.sortedBy { it.name }.toTypedArray(),
                )
                .onConflict(ACTIVATION_ROUTE.ACTIVATION_ID).doNothing().awaitSingle()
        }
    }

    override suspend fun activate(lease: WorkLease, nextHealthcheckAt: Instant) {
        transaction.transaction { dsl ->
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
        transaction.transaction { dsl ->
            val activation = dsl.lockActivation(lease)
            dsl.changeState(activation, ActivationState.FAILED, reason)
            dsl.deleteFrom(ACTIVATION_WORK)
                .where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId))
                .awaitSingle()
        }
    }
}
