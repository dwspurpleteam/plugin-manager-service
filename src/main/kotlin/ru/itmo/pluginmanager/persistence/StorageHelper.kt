package ru.itmo.pluginmanager.persistence

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.jboss.logging.Logger
import org.jooq.DSLContext
import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.domain.ActivationRequest
import ru.itmo.pluginmanager.domain.ActivationRoute
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.MediaType
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_ROUTE
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_STATE_HISTORY
import ru.itmo.pluginmanager.jooq.generated.Tables.ACTIVATION_WORK
import ru.itmo.pluginmanager.jooq.generated.Tables.PLUGIN_ACTIVATION
import ru.itmo.pluginmanager.jooq.generated.tables.records.PluginActivationRecord
import ru.itmo.pluginmanager.worker.WorkLease
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationState as DatabaseActivationState
import ru.itmo.pluginmanager.jooq.generated.enums.ActivationWorkKind as DatabaseActivationWorkKind

private val logger = Logger.getLogger("ru.itmo.pluginmanager.persistence.ActivationState")

internal fun storageError(code: ErrorCode = ErrorCode.INTERNAL_ERROR): Nothing =
    throw PluginManagerException(code, "Activation storage operation rejected")

internal suspend fun DSLContext.lockActivation(lease: WorkLease): PluginActivationRecord {
    val work = selectFrom(ACTIVATION_WORK).where(ACTIVATION_WORK.ACTIVATION_ID.eq(lease.activationId))
        .and(ACTIVATION_WORK.LEASE_TOKEN.eq(lease.token))
        .and(ACTIVATION_WORK.KIND.eq(DatabaseActivationWorkKind.valueOf(lease.kind.name)))
        .and(ACTIVATION_WORK.LEASE_UNTIL.gt(org.jooq.impl.DSL.field("clock_timestamp()", OffsetDateTime::class.java)))
        .forUpdate().awaitFirstOrNull() ?: storageError(ErrorCode.LEASE_LOST)
    val activation = selectFrom(PLUGIN_ACTIVATION).where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(work.activationId))
        .forUpdate().awaitFirstOrNull() ?: storageError()
    if (activation.state != DatabaseActivationState.ACTIVATING &&
        activation.state != DatabaseActivationState.ACTIVE
    ) {
        storageError(ErrorCode.LEASE_LOST)
    }
    return activation
}

internal suspend fun DSLContext.changeState(
    record: PluginActivationRecord,
    state: ActivationState,
    reason: ErrorCode?,
) {
    val changed = OffsetDateTime.now(ZoneOffset.UTC)
    val revision = record.stateRevision + 1
    val databaseState = DatabaseActivationState.valueOf(state.name)
    update(PLUGIN_ACTIVATION).set(PLUGIN_ACTIVATION.STATE, databaseState)
        .set(PLUGIN_ACTIVATION.STATE_REVISION, revision)
        .set(PLUGIN_ACTIVATION.STATE_CHANGED_AT, changed)
        .set(PLUGIN_ACTIVATION.FAILURE_CODE, reason?.name)
        .where(PLUGIN_ACTIVATION.ACTIVATION_ID.eq(record.activationId)).awaitSingle()
    insertInto(ACTIVATION_STATE_HISTORY)
        .set(ACTIVATION_STATE_HISTORY.HISTORY_ID, UUID.randomUUID())
        .set(ACTIVATION_STATE_HISTORY.ACTIVATION_ID, record.activationId)
        .set(ACTIVATION_STATE_HISTORY.REVISION, revision)
        .set(ACTIVATION_STATE_HISTORY.FROM_STATE, record.state)
        .set(ACTIVATION_STATE_HISTORY.TO_STATE, databaseState)
        .set(ACTIVATION_STATE_HISTORY.CHANGED_AT, changed)
        .set(ACTIVATION_STATE_HISTORY.REASON_CODE, reason?.name).awaitSingle()
    if (state == ActivationState.FAILED) {
        logger.errorf(
            "Activation transitioned to FAILED: activationId=%s, failureCode=%s",
            record.activationId,
            reason,
        )
    }
}

internal fun PluginActivationRecord.toDomain(): Activation = Activation(
    activationId,
    ActivationRequest(callId, pluginId, requestedBy, idempotencyKey, requestedVersion, requestedRegistryId),
    resolvedVersion?.let { PluginSelection(pluginId, it, resolvedRegistryId) },
    ActivationState.valueOf(state.name),
    stateRevision,
    createdAt.toInstant(),
    stateChangedAt.toInstant(),
    failureCode?.let(ErrorCode::valueOf),
)

internal suspend fun DSLContext.readRoute(id: UUID): ActivationRoute? =
    selectFrom(ACTIVATION_ROUTE).where(ACTIVATION_ROUTE.ACTIVATION_ID.eq(id)).awaitFirstOrNull()?.let {
        ActivationRoute(
            it.routeId,
            id,
            it.signalDst,
            it.allowedEventTypes?.toSet(),
            it.allowedEventGroups?.toSet(),
            it.healthcheckUrl,
            it.mediaTypes.map { mediaType -> MediaType.valueOf(mediaType.name) }.toSet(),
        )
    }
